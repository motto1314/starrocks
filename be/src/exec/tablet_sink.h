// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/exec/tablet_sink.h

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include "exec/tablet_sink_sender.h"

namespace starrocks {

class AddBatchCounter;
class NodeChannel;
class IndexChannel;
class TabletSinkSender;

// Write data to Olap Table.
// When OlapTableSink::open() called, there will be a consumer thread running in the background.
// When you call OlapTableSink::send(), you will be the productor who products pending batches.
// Join the consumer thread in close().
class OlapTableSink : public AsyncDataSink {
public:
    // Construct from thrift struct which is generated by FE.
    OlapTableSink(ObjectPool* pool, const std::vector<TExpr>& texprs, Status* status, RuntimeState* state);
    ~OlapTableSink() override = default;

    Status init(const TDataSink& sink, RuntimeState* state) override;

    Status prepare(RuntimeState* state) override;

    // sync open interface
    Status open(RuntimeState* state) override;

    // async open interface: try_open() -> [is_open_done()] -> open_wait()
    // if is_open_done() return true, open_wait() will not block
    // otherwise open_wait() will block
    Status try_open(RuntimeState* state) override;

    bool is_open_done() override;

    Status open_wait() override;

    // if is_full() return false, add_chunk() will not block
    Status send_chunk(RuntimeState* state, Chunk* chunk) override;

    // async add chunk interface
    Status send_chunk_nonblocking(RuntimeState* state, Chunk* chunk) override;

    bool is_full() override;

    // async close interface: try_close() -> [is_close_done()] -> close_wait()
    // if is_close_done() return true, close_wait() will not block
    // otherwise close_wait() will block
    Status try_close(RuntimeState* state) override { return _tablet_sink_sender->try_close(state); }

    Status close_wait(RuntimeState* state, Status close_status) override;

    bool is_close_done() override;

    // sync close() interface
    Status close(RuntimeState* state, Status close_status) override;

    // This should be called in OlapTableSinkOperator::prepare only once
    void set_profile(RuntimeProfile* profile) override;
    // Returns the runtime profile for the sink.
    RuntimeProfile* profile() override { return _profile; }

    ObjectPool* pool() { return _pool; }

    Status reset_epoch(RuntimeState* state);

    TabletSinkProfile* ts_profile() const { return _ts_profile; }

private:
    void _prepare_profile(RuntimeState* state);

    template <LogicalType LT>
    void _validate_decimal(RuntimeState* state, Chunk* chunk, Column* column, const SlotDescriptor* desc,
                           std::vector<uint8_t>* validate_selection);
    // This method will change _validate_selection
    void _validate_data(RuntimeState* state, Chunk* chunk);

    Status _init_node_channels(RuntimeState* state, IndexIdToTabletBEMap& index_id_to_tablet_be_map);

    // When compute buckect hash, we should use real string for char column.
    // So we need to pad char column after compute buckect hash.
    void _padding_char_column(Chunk* chunk);

    void _print_varchar_error_msg(RuntimeState* state, const Slice& str, SlotDescriptor* desc);

    static void _print_decimal_error_msg(RuntimeState* state, const DecimalV2Value& decimal, SlotDescriptor* desc);

    Status _fill_auto_increment_id(Chunk* chunk);

    Status _fill_auto_increment_id_internal(Chunk* chunk, SlotDescriptor* slot, int64_t table_id);

    void mark_as_failed(const NodeChannel* ch) { _failed_channels.insert(ch->node_id()); }
    bool is_failed_channel(const NodeChannel* ch) { return _failed_channels.count(ch->node_id()) != 0; }
    bool has_intolerable_failure() {
        if (_write_quorum_type == TWriteQuorumType::ALL) {
            return _failed_channels.size() > 0;
        } else if (_write_quorum_type == TWriteQuorumType::ONE) {
            return _failed_channels.size() >= _num_repicas;
        } else {
            return _failed_channels.size() >= ((_num_repicas + 1) / 2);
        }
    }

    void for_each_node_channel(const std::function<void(NodeChannel*)>& func) {
        for (auto& it : _node_channels) {
            func(it.second.get());
        }
    }

    void for_each_index_channel(const std::function<void(NodeChannel*)>& func) {
        for (auto& index_channel : _channels) {
            index_channel->for_each_node_channel(func);
        }
    }

    Status _automatic_create_partition();
    Status _update_immutable_partition(const std::set<int64_t>& partition_ids);

    Status _incremental_open_node_channel(const std::vector<TOlapTablePartition>& partitions);

    Status _send_chunk(RuntimeState* state, Chunk* chunk, bool nonblocking);

    friend class NodeChannel;
    friend class IndexChannel;

    ObjectPool* _pool;
    int64_t _rpc_http_min_size = 0;

    // unique load id
    PUniqueId _load_id;
    int64_t _txn_id = -1;
    int64_t _sink_id = 0;
    std::string _txn_trace_parent;
    Span _span;
    int _num_repicas = -1;
    bool _need_gen_rollup = false;
    int _tuple_desc_id = -1;
    std::string _merge_condition;
    std::string _encryption_meta;
    TPartialUpdateMode::type _partial_update_mode;

    // this is tuple descriptor of destination OLAP table
    TupleDescriptor* _output_tuple_desc = nullptr;
    std::vector<ExprContext*> _output_expr_ctxs;

    // number of senders used to insert into OlapTable, if we only support single node insert,
    // all data from select should collectted and then send to OlapTable.
    // To support multiple senders, we maintain a channel for each sender.
    int _sender_id = -1;
    int _num_senders = -1;
    bool _is_lake_table = false;
    bool _write_txn_log = false;

    TKeysType::type _keys_type;

    // TODO(zc): think about cache this data
    std::shared_ptr<OlapTableSchemaParam> _schema;
    OlapTablePartitionParam* _vectorized_partition = nullptr;
    StarRocksNodesInfo* _nodes_info = nullptr;
    OlapTableLocationParam* _location = nullptr;

    std::vector<DecimalV2Value> _max_decimalv2_val;
    std::vector<DecimalV2Value> _min_decimalv2_val;

    // one chunk selection index for partition validation and data validation
    std::vector<uint16_t> _validate_select_idx;
    // one chunk selection for data validation
    std::vector<uint8_t> _validate_selection;

    RuntimeProfile* _profile = nullptr;
    TabletSinkProfile* _ts_profile = nullptr;

    // index_channel
    std::vector<std::unique_ptr<IndexChannel>> _channels;
    std::vector<OlapTablePartition*> _partitions;
    std::unordered_map<int64_t, std::set<int64_t>> _index_id_partition_ids;
    std::vector<uint32_t> _tablet_indexes;
    // Store the output expr comput result column
    std::unique_ptr<Chunk> _output_chunk;
    bool _open_done{false};

    std::unique_ptr<TabletSinkSender> _tablet_sink_sender;

    // Stats for this
    int64_t _convert_batch_ns = 0;
    int64_t _validate_data_ns = 0;
    int64_t _number_input_rows = 0;
    int64_t _number_output_rows = 0;
    int64_t _number_filtered_rows = 0;
    // load mem limit is for remote load channel
    int64_t _load_mem_limit = 0;
    // the timeout of load channels opened by this tablet sink. in second
    int64_t _load_channel_timeout_s = 0;

    // BeId -> channel
    std::unordered_map<int64_t, std::unique_ptr<NodeChannel>> _node_channels;
    // BeId
    std::set<int64_t> _failed_channels;
    // enable colocate index
    bool _colocate_mv_index{false};

    bool _enable_replicated_storage = false;
    TWriteQuorumType::type _write_quorum_type = TWriteQuorumType::MAJORITY;

    SlotId _auto_increment_slot_id = -1;
    bool _has_auto_increment = false;
    bool _null_expr_in_auto_increment = false;
    bool _miss_auto_increment_column = false;

    std::unique_ptr<ThreadPoolToken> _automatic_partition_token;
    std::vector<std::vector<std::string>> _partition_not_exist_row_values;
    bool _enable_automatic_partition = false;
    bool _has_automatic_partition = false;
    std::atomic<bool> _is_automatic_partition_running = false;
    Status _automatic_partition_status;

    bool _ignore_out_of_partition = false;

    // bucket size for automatic bucket
    int64_t _automatic_bucket_size = 0;
    std::set<int64_t> _immutable_partition_ids;
    RuntimeState* _state = nullptr;

    PLoadChannelProfileConfig _load_channel_profile_config;
};

} // namespace starrocks
