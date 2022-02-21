// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#pragma once
#include <memory>
#include <mutex>

#include "common/statusor.h"
#include "exec/vectorized/hash_join_node.h"
#include "exprs/expr_context.h"
#include "exprs/predicate.h"
#include "exprs/vectorized/runtime_filter_bank.h"

namespace starrocks {
namespace pipeline {
class RuntimeFilterHolder;
using RuntimeFilterHolderPtr = std::unique_ptr<RuntimeFilterHolder>;
using RuntimeInFilter = starrocks::ExprContext;
using RuntimeBloomFilter = starrocks::vectorized::RuntimeFilterBuildDescriptor;
using RuntimeBloomFilterProbeDescriptor = starrocks::vectorized::RuntimeFilterProbeDescriptor;
using RuntimeBloomFilterProbeDescriptorPtr = RuntimeBloomFilterProbeDescriptor*;
using RuntimeBloomFilterRunningContext = starrocks::vectorized::JoinRuntimeFilter::RunningContext;
using RuntimeInFilterPtr = RuntimeInFilter*;
using RuntimeBloomFilterPtr = RuntimeBloomFilter*;
using RuntimeInFilters = std::list<RuntimeInFilterPtr>;
using RuntimeBloomFilters = std::list<RuntimeBloomFilterPtr>;
struct RuntimeFilterCollector;
using RuntimeFilterCollectorPtr = std::unique_ptr<RuntimeFilterCollector>;
using RuntimeFilterProbeCollector = starrocks::vectorized::RuntimeFilterProbeCollector;
using Predicate = starrocks::Predicate;
struct RuntimeBloomFilterBuildParam;
using RuntimeBloomFilterBuildParams = std::list<RuntimeBloomFilterBuildParam>;
// Parameters used to build runtime bloom-filters.
struct RuntimeBloomFilterBuildParam {
    RuntimeBloomFilterBuildParam(bool eq_null, const ColumnPtr& column, size_t ht_row_count)
            : eq_null(eq_null), column(column), ht_row_count(ht_row_count) {}
    bool eq_null;
    ColumnPtr column;
    size_t ht_row_count;
};

// RuntimeFilterCollector contains runtime in-filters and bloom-filters, it is stored in RuntimeFilerHub
// and every HashJoinBuildOperatorFactory has its corresponding RuntimeFilterCollector.
struct RuntimeFilterCollector {
    RuntimeFilterCollector(RuntimeInFilters&& in_filters, RuntimeBloomFilters&& bloom_filters)
            : _in_filters(std::move(in_filters)), _bloom_filters(std::move(bloom_filters)) {}

    RuntimeBloomFilters& get_bloom_filters() { return _bloom_filters; }
    RuntimeInFilters& get_in_filters() { return _in_filters; }

    // In-filters are constructed by a node and may be pushed down to its descendant node.
    // Different tuple id and slot id between descendant and ancestor nodes may be referenced to the same column,
    // such as ProjectNode, so we need use ancestor's tuple slot mappings to rewrite in filters.
    void rewrite_in_filters(const std::vector<TupleSlotMapping>& mappings) {
        std::vector<TupleId> tuple_ids(1);
        for (const auto& mapping : mappings) {
            tuple_ids[0] = mapping.to_tuple_id;

            for (auto in_filter : _in_filters) {
                if (!in_filter->root()->is_bound(tuple_ids)) {
                    continue;
                }

                DCHECK(nullptr != dynamic_cast<vectorized::ColumnRef*>(in_filter->root()->get_child(0)));
                auto column = ((vectorized::ColumnRef*)in_filter->root()->get_child(0));

                if (column->slot_id() == mapping.to_slot_id) {
                    column->set_slot_id(mapping.from_slot_id);
                    column->set_tuple_id(mapping.from_tuple_id);
                }
            }
        }
    }

    std::vector<RuntimeInFilterPtr> get_in_filters_bounded_by_tuple_ids(const std::vector<TupleId>& tuple_ids) {
        std::vector<ExprContext*> selected_in_filters;
        for (auto* in_filter : _in_filters) {
            if (in_filter->root()->is_bound(tuple_ids)) {
                selected_in_filters.push_back(in_filter);
            }
        }
        return selected_in_filters;
    }

private:
    // local runtime in-filter
    RuntimeInFilters _in_filters;
    // global/local runtime bloom-filter(including max-min filter)
    RuntimeBloomFilters _bloom_filters;
};

class RuntimeFilterHolder {
public:
    void set_collector(RuntimeFilterCollectorPtr&& collector) {
        _collector_ownership = std::move(collector);
        _collector.store(_collector_ownership.get(), std::memory_order_release);
    }
    RuntimeFilterCollector* get_collector() { return _collector.load(std::memory_order_acquire); }
    bool is_ready() { return get_collector() != nullptr; }

private:
    RuntimeFilterCollectorPtr _collector_ownership;
    std::atomic<RuntimeFilterCollector*> _collector;
};

// RuntimeFilterHub is a mediator that used to gather all runtime filters generated by HashJoinBuildOperator instances.
// It has a RuntimeFilterHolder for each HashJoinBuilder instance, when total runtime filter is generated, then it is
// added into RuntimeFilterHub; the operators consuming runtime filters inspect RuntimeFilterHub and find out its bounded
// runtime filters. RuntimeFilterHub is reserved beforehand, and there is no need to use mutex to guard concurrent access.
class RuntimeFilterHub {
public:
    void add_holder(TPlanNodeId id) { _holders.emplace(std::make_pair(id, std::make_unique<RuntimeFilterHolder>())); }
    void set_collector(TPlanNodeId id, RuntimeFilterCollectorPtr&& collector) {
        get_holder(id)->set_collector(std::move(collector));
    }

    RuntimeBloomFilters& get_bloom_filters(TPlanNodeId id) {
        return get_holder(id)->get_collector()->get_bloom_filters();
    }

    void close_all_in_filters(RuntimeState* state) {
        for (auto& [_, holder] : _holders) {
            if (auto* collector = holder->get_collector()) {
                for (auto& in_filter : collector->get_in_filters()) {
                    in_filter->close(state);
                }
            }
        }
    }

    std::vector<RuntimeFilterHolder*> gather_holders(const std::set<TPlanNodeId>& ids) {
        std::vector<RuntimeFilterHolder*> holders;
        holders.reserve(ids.size());
        for (auto id : ids) {
            holders.push_back(get_holder(id).get());
        }
        return holders;
    }

private:
    RuntimeFilterHolderPtr& get_holder(TPlanNodeId id) {
        auto it = _holders.find(id);
        DCHECK(it != _holders.end());
        return it->second;
    }
    // Each HashJoinBuildOperatorFactory has a corresponding Holder indexed by its TPlanNodeId.
    std::unordered_map<TPlanNodeId, RuntimeFilterHolderPtr> _holders;
};

// A ExecNode in non-pipeline engine can be decomposed into more than one OperatorFactories in pipeline engine.
// Pipeline framework do not care about that runtime filters take affects on which OperatorFactories, since
// it depends on Operators' implementation. so each OperatorFactory from the same ExecNode shared a
// RefCountedRuntimeFilterProbeCollector, in which refcount is introduced to guarantee that both prepare and
// close method of the RuntimeFilterProbeCollector inside this wrapper object is called only exactly-once.
class RefCountedRuntimeFilterProbeCollector;
using RefCountedRuntimeFilterProbeCollectorPtr = std::shared_ptr<RefCountedRuntimeFilterProbeCollector>;
class RefCountedRuntimeFilterProbeCollector {
public:
    RefCountedRuntimeFilterProbeCollector(size_t num_operators_generated,
                                          RuntimeFilterProbeCollector&& rf_probe_collector)
            : _count((num_operators_generated << 32) | num_operators_generated),
              _num_operators_generated(num_operators_generated),
              _rf_probe_collector(std::move(rf_probe_collector)) {}

    Status prepare(RuntimeState* state, const RowDescriptor& row_desc, RuntimeProfile* p) {
        if ((_count.fetch_sub(1) & 0xffff'ffffull) == _num_operators_generated) {
            RETURN_IF_ERROR(_rf_probe_collector.prepare(state, row_desc, p));
            RETURN_IF_ERROR(_rf_probe_collector.open(state));
        }
        return Status::OK();
    }

    void close(RuntimeState* state) {
        static constexpr size_t k = 1ull << 32;
        if (_count.fetch_sub(k) == k) {
            _rf_probe_collector.close(state);
        }
    }

    RuntimeFilterProbeCollector* get_rf_probe_collector() { return &_rf_probe_collector; }

private:
    // a refcount, low 32 bit used count the close invocation times, and the high 32 bit used to count the
    // prepare invocation times.
    std::atomic<size_t> _count;
    // how many OperatorFactories into whom a ExecNode is decomposed.
    const size_t _num_operators_generated;
    // a wrapped RuntimeFilterProbeCollector initialized by a ExecNode, which contains runtime bloom filters.
    RuntimeFilterProbeCollector _rf_probe_collector;
};

// Used to merge runtime in-filters and bloom-filters generated by multiple HashJoinBuildOperator instances.
// When more than one HashJoinBuildOperator instances are appended to LocalExchangeSourceOperator(PartitionExchanger)
// instances, the build side table is partitioned, and each HashJoinBuildOperator instance is applied to the
// partition and a partial filter is generated after hash table has been constructed. the partial filters can
// not take effects on operators in front of LocalExchangeSourceOperators before they are merged into a total one.
class PartialRuntimeFilterMerger {
public:
    PartialRuntimeFilterMerger(ObjectPool* pool, size_t limit, size_t num_builders)
            : _pool(pool),
              _limit(limit),
              _num_active_builders(num_builders),
              _ht_row_counts(num_builders),
              _partial_in_filters(num_builders),
              _partial_bloom_filter_build_params(num_builders) {}

    // HashJoinBuildOperator call add_partial_filters to gather partial runtime filters. the last HashJoinBuildOperator
    // will merge partial runtime filters into total one finally.
    StatusOr<bool> add_partial_filters(
            size_t idx, size_t ht_row_count, std::list<ExprContext*>&& partial_in_filters,
            std::list<RuntimeBloomFilterBuildParam>&& partial_bloom_filter_build_params,
            std::list<vectorized::RuntimeFilterBuildDescriptor*>&& bloom_filter_descriptors) {
        DCHECK(idx < _partial_bloom_filter_build_params.size());
        // both _ht_row_counts, _partial_in_filters, _partial_bloom_filter_build_params are reserved beforehand,
        // each HashJoinBuildOperator mutates its corresponding slot indexed by driver_sequence, so concurrent
        // access need mutex to guard.
        _ht_row_counts[idx] = ht_row_count;
        _partial_in_filters[idx] = std::move(partial_in_filters);
        _partial_bloom_filter_build_params[idx] = std::move(partial_bloom_filter_build_params);
        // merge
        if (1 == _num_active_builders--) {
            _bloom_filter_descriptors = std::move(bloom_filter_descriptors);
            merge_in_filters();
            merge_bloom_filters();
            return true;
        }
        return false;
    }

    RuntimeInFilters get_total_in_filters() { return _partial_in_filters[0]; }

    RuntimeBloomFilters get_total_bloom_filters() { return _bloom_filter_descriptors; }

    Status merge_in_filters() {
        bool can_merge_in_filters = true;
        size_t num_rows = 0;
        ssize_t k = -1;
        //squeeze _partial_in_filters and eliminate empty in-filter lists generated by empty hash tables.
        for (auto i = 0; i < _ht_row_counts.size(); ++i) {
            auto& in_filters = _partial_in_filters[i];
            // empty in-filter list is generated by empty hash tables, so skip it.
            if (_ht_row_counts[i] == 0) {
                continue;
            }
            // empty in-filter list is generated by non-empty hash tables(size>1024), in-filters can not be merged.
            if (in_filters.empty()) {
                can_merge_in_filters = false;
                break;
            }
            // move in-filter list indexed by i to slot indexed by k, eliminates holes in the middle.
            ++k;
            if (k < i) {
                _partial_in_filters[k] = std::move(_partial_in_filters[i]);
            }
            num_rows = std::max(num_rows, _ht_row_counts[i]);
        }

        can_merge_in_filters = can_merge_in_filters && (num_rows <= 1024) && k >= 0;
        if (!can_merge_in_filters) {
            _partial_in_filters[0].clear();
            return Status::OK();
        }
        // only merge k partial in-filter list
        _partial_in_filters.resize(k + 1);

        auto& total_in_filters = _partial_in_filters[0];
        for (auto i = 1; i < _partial_in_filters.size(); ++i) {
            auto& in_filters = _partial_in_filters[i];
            auto total_in_filter_it = total_in_filters.begin();
            auto in_filter_it = in_filters.begin();
            while (total_in_filter_it != total_in_filters.end()) {
                auto& total_in_filter = *(total_in_filter_it++);
                auto& in_filter = *(in_filter_it++);
                if (total_in_filter == nullptr || in_filter == nullptr) {
                    total_in_filter = nullptr;
                    continue;
                }
                auto* total_in_filter_pred = down_cast<Predicate*>(total_in_filter->root());
                auto* in_filter_pred = down_cast<Predicate*>(in_filter->root());
                RETURN_IF_ERROR(total_in_filter_pred->merge(in_filter_pred));
            }
        }
        total_in_filters.erase(std::remove(total_in_filters.begin(), total_in_filters.end(), nullptr),
                               total_in_filters.end());
        return Status::OK();
    }

    Status merge_bloom_filters() {
        if (_partial_bloom_filter_build_params.empty()) {
            return Status::OK();
        }
        size_t row_count = 0;
        for (auto count : _ht_row_counts) {
            row_count += count;
        }
        for (auto& desc : _bloom_filter_descriptors) {
            desc->set_is_pipeline(true);
            // skip if it does not have consumer.
            if (!desc->has_consumer()) continue;
            // skip if ht.size() > limit, and it's only for local.
            if (!desc->has_remote_targets() && row_count > _limit) continue;
            PrimitiveType build_type = desc->build_expr_type();
            vectorized::JoinRuntimeFilter* filter =
                    vectorized::RuntimeFilterHelper::create_runtime_bloom_filter(_pool, build_type);
            if (filter == nullptr) continue;
            filter->init(row_count);
            filter->set_join_mode(desc->join_mode());
            desc->set_runtime_filter(filter);
        }

        for (auto& params : _partial_bloom_filter_build_params) {
            auto desc_it = _bloom_filter_descriptors.begin();
            auto param_it = params.begin();
            while (param_it != params.end()) {
                auto& desc = *(desc_it++);
                auto& param = *(param_it++);
                if (desc->runtime_filter() == nullptr || param.column == nullptr) {
                    continue;
                }
                auto status = vectorized::RuntimeFilterHelper::fill_runtime_bloom_filter(
                        param.column, desc->build_expr_type(), desc->runtime_filter(),
                        vectorized::kHashJoinKeyColumnOffset, param.eq_null);
                if (!status.ok()) {
                    desc->set_runtime_filter(nullptr);
                }
            }
        }
        return Status::OK();
    }

private:
    ObjectPool* _pool;
    const size_t _limit;
    std::atomic<size_t> _num_active_builders;
    std::vector<size_t> _ht_row_counts;
    std::vector<RuntimeInFilters> _partial_in_filters;
    std::vector<RuntimeBloomFilterBuildParams> _partial_bloom_filter_build_params;
    RuntimeBloomFilters _bloom_filter_descriptors;
};

} // namespace pipeline
} // namespace starrocks
