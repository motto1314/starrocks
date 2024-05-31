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

package com.starrocks.scheduler;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Uninterruptibles;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.SlotRef;
import com.starrocks.catalog.BaseTableInfo;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.DataProperty;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.DistributionInfo;
import com.starrocks.catalog.ExpressionRangePartitionInfo;
import com.starrocks.catalog.HashDistributionInfo;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionInfo;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.RangePartitionInfo;
import com.starrocks.catalog.ResourceGroup;
import com.starrocks.catalog.SinglePartitionInfo;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.TableProperty;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.MaterializedViewExceptions;
import com.starrocks.common.Pair;
import com.starrocks.common.UserException;
import com.starrocks.common.io.DeepCopy;
import com.starrocks.common.util.DebugUtil;
import com.starrocks.common.util.PropertyAnalyzer;
import com.starrocks.common.util.RangeUtils;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.common.util.concurrent.lock.LockTimeoutException;
import com.starrocks.connector.ConnectorPartitionTraits;
import com.starrocks.connector.PartitionUtil;
import com.starrocks.lake.LakeMaterializedView;
import com.starrocks.lake.LakeTable;
import com.starrocks.metric.IMaterializedViewMetricsEntity;
import com.starrocks.metric.MaterializedViewMetricsRegistry;
import com.starrocks.persist.ChangeMaterializedViewRefreshSchemeLog;
import com.starrocks.planner.HdfsScanNode;
import com.starrocks.planner.OlapScanNode;
import com.starrocks.planner.ScanNode;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.scheduler.mv.MVPCTRefreshPlanBuilder;
import com.starrocks.scheduler.persist.MVTaskRunExtraMessage;
import com.starrocks.scheduler.persist.TaskRunStatus;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.StatementPlanner;
import com.starrocks.sql.analyzer.AnalyzerUtils;
import com.starrocks.sql.ast.AddPartitionClause;
import com.starrocks.sql.ast.DistributionDesc;
import com.starrocks.sql.ast.DropPartitionClause;
import com.starrocks.sql.ast.HashDistributionDesc;
import com.starrocks.sql.ast.InsertStmt;
import com.starrocks.sql.ast.PartitionDesc;
import com.starrocks.sql.ast.PartitionKeyDesc;
import com.starrocks.sql.ast.PartitionNames;
import com.starrocks.sql.ast.PartitionValue;
import com.starrocks.sql.ast.RandomDistributionDesc;
import com.starrocks.sql.ast.RangePartitionDesc;
import com.starrocks.sql.ast.SingleRangePartitionDesc;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.common.DmlException;
import com.starrocks.sql.common.PartitionDiffer;
import com.starrocks.sql.common.QueryDebugOptions;
import com.starrocks.sql.common.RangePartitionDiff;
import com.starrocks.sql.common.SyncPartitionUtils;
import com.starrocks.sql.optimizer.rule.transformation.materialization.MvUtils;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.sql.plan.ExecPlan;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.starrocks.catalog.system.SystemTable.MAX_FIELD_VARCHAR_LENGTH;

/**
 * Core logic of materialized view refresh task run
 * PartitionBasedMvRefreshProcessor is not thread safe for concurrent runs of the same materialized view
 */
public class PartitionBasedMvRefreshProcessor extends BaseTaskRunProcessor {

    private static final Logger LOG = LogManager.getLogger(PartitionBasedMvRefreshProcessor.class);

    public static final String MV_ID = "mvId";
    // session.enable_spill
    public static final String MV_SESSION_ENABLE_SPILL =
            PropertyAnalyzer.PROPERTIES_MATERIALIZED_VIEW_SESSION_PREFIX + SessionVariable.ENABLE_SPILL;
    // session.query_timeout
    public static final String MV_SESSION_TIMEOUT =
            PropertyAnalyzer.PROPERTIES_MATERIALIZED_VIEW_SESSION_PREFIX + SessionVariable.QUERY_TIMEOUT;
    // default query timeout for mv: 1 hour
    private static final int MV_DEFAULT_QUERY_TIMEOUT = 3600;

    private static final int CREATE_PARTITION_BATCH_SIZE = 64;

    private Database database;
    private MaterializedView materializedView;
    private MvTaskRunContext mvContext;
    // table id -> <base table info, snapshot table>
    private Map<Long, Pair<BaseTableInfo, Table>> snapshotBaseTables = Maps.newHashMap();

    private long oldTransactionVisibleWaitTimeout;

    // represents the refresh job final job status
    public enum RefreshJobStatus {
        SUCCESS,
        FAILED,
        EMPTY,
    }

    @VisibleForTesting
    public MvTaskRunContext getMvContext() {
        return mvContext;
    }

    @VisibleForTesting
    public void setMvContext(MvTaskRunContext mvContext) {
        this.mvContext = mvContext;
    }

    // Core logics:
    // 1. prepare to check some conditions
    // 2. sync partitions with base tables(add or drop partitions, which will be optimized  by dynamic partition creation later)
    // 3. decide which partitions of materialized view to refresh and the corresponding base tables' source partitions
    // 4. construct the refresh sql and execute it
    // 5. update the source table version map if refresh task completes successfully
    @Override
    public void processTaskRun(TaskRunContext context) throws Exception {
        prepare(context);

        Preconditions.checkState(materializedView != null);
        IMaterializedViewMetricsEntity mvEntity =
                MaterializedViewMetricsRegistry.getInstance().getMetricsEntity(materializedView.getMvId());

        try {
            RefreshJobStatus status = doMvRefresh(context, mvEntity);
            mvEntity.increaseRefreshJobStatus(status);
        } catch (Exception e) {
            mvEntity.increaseRefreshJobStatus(RefreshJobStatus.FAILED);
            throw e;
        } finally {
            postProcess();
        }
    }

    /**
     * Sync partitions of base tables and check whether they are changing anymore
     */
    private boolean syncAndCheckPartitions(TaskRunContext context, IMaterializedViewMetricsEntity mvEntity) {
        // collect partition infos of ref base tables
        int retryNum = 0;
        boolean checked = false;
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (!checked && retryNum++ < Config.max_mv_check_base_table_change_retry_times) {
            mvEntity.increaseRefreshRetryMetaCount(1L);
            // refresh external table meta cache before sync partitions
            refreshExternalTable(context);
            // sync partitions between materialized view and base tables out of lock
            // do it outside lock because it is a time-cost operation
            syncPartitions(context);
            // check whether there are partition changes for base tables, eg: partition rename
            // retry to sync partitions if any base table changed the partition infos
            if (checkBaseTablePartitionChange(materializedView)) {
                LOG.info("materialized view:{} base partition has changed. retry to sync partitions, retryNum:{}",
                        materializedView.getName(), retryNum);
                // sleep 100ms
                Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
                continue;
            }
            checked = true;
        }
        LOG.info("materialized view {} after checking partitions change {} times: {}, costs: {} ms",
                materializedView.getName(), retryNum, checked, stopwatch.elapsed(TimeUnit.MILLISECONDS));
        return checked;
    }

    private Set<String> checkMvToRefreshedPartitions(TaskRunContext context) throws AnalysisException {
        if (!database.tryReadLock(Config.mv_refresh_try_lock_timeout_ms, TimeUnit.MILLISECONDS)) {
            throw new LockTimeoutException("Failed to lock database: " + database.getFullName());
        }
        try {
            Set<String> mvToRefreshedPartitions = getPartitionsToRefreshForMaterializedView(context.getProperties());
            if (mvToRefreshedPartitions.isEmpty()) {
                LOG.info("no partitions to refresh for materialized view {}", materializedView.getName());
                return mvToRefreshedPartitions;
            }
            // Only refresh the first partition refresh number partitions, other partitions will generate new tasks
            filterPartitionByRefreshNumber(mvToRefreshedPartitions, materializedView);
            LOG.info("materialized view partitions to refresh:{}", mvToRefreshedPartitions);
            return mvToRefreshedPartitions;
        } finally {
            database.readUnlock();
        }
    }

    private RefreshJobStatus doMvRefresh(TaskRunContext context, IMaterializedViewMetricsEntity mvEntity) {
        long startRefreshTs = System.currentTimeMillis();

        // refresh materialized view
        RefreshJobStatus result = doRefreshMaterializedViewWithRetry(context, mvEntity);

        // do not generate next task run if the current task run is killed
        if (mvContext.hasNextBatchPartition() && !mvContext.getTaskRun().isKilled()) {
            generateNextTaskRun();
        }

        long refreshDurationMs = System.currentTimeMillis() - startRefreshTs;
        LOG.info("Refresh {} success, cost time(s): {}", materializedView.getName(),
                DebugUtil.DECIMAL_FORMAT_SCALE_3.format(refreshDurationMs / 1000.0));
        mvEntity.updateRefreshDuration(refreshDurationMs);
        return result;
    }

    private void logMvToRefreshInfoIntoTaskRun(Set<String> finalMvToRefreshedPartitions,
                                               Map<String, Set<String>> finalRefTablePartitionNames) {
        updateTaskRunStatus(status -> {
            MVTaskRunExtraMessage extraMessage = status.getMvTaskRunExtraMessage();
            extraMessage.setMvPartitionsToRefresh(finalMvToRefreshedPartitions);
            extraMessage.setRefBasePartitionsToRefreshMap(finalRefTablePartitionNames);
        });
    }

    /**
     * Update task run status's extra message to add more information for information_schema if possible.
     *
     * @param action
     */
    private void updateTaskRunStatus(Consumer<TaskRunStatus> action) {
        if (this.mvContext.status != null) {
            action.accept(this.mvContext.status);
        }
    }

    /**
     * Retry the `doRefreshMaterializedView` method to avoid insert fails in occasional cases.
     */
    private RefreshJobStatus doRefreshMaterializedViewWithRetry(TaskRunContext taskRunContext,
                                                                IMaterializedViewMetricsEntity mvEntity) throws DmlException {
        // Use current connection variables instead of mvContext's session variables to be better debug.
        int maxRefreshMaterializedViewRetryNum = getMaxRefreshMaterializedViewRetryNum(taskRunContext.getCtx());

        Throwable lastException = null;
        int lockFailedTimes = 0;
        int refreshFailedTimes = 0;
        while (refreshFailedTimes < maxRefreshMaterializedViewRetryNum &&
                lockFailedTimes < Config.max_mv_refresh_try_lock_failure_retry_times) {
            try {
                return doRefreshMaterializedView(taskRunContext, mvEntity);
            } catch (LockTimeoutException e) {
                // if lock timeout, retry to refresh
                lockFailedTimes += 1;
                LOG.warn("Refresh materialized view {} failed at {}th time because try lock failed: {}",
                        this.materializedView.getName(), lockFailedTimes, e);
                lastException = e;
            } catch (Throwable e) {
                refreshFailedTimes += 1;
                LOG.warn("Refresh materialized view {} failed at {}th time: {}",
                        this.materializedView.getName(), refreshFailedTimes, e);
                lastException = e;
            }

            // sleep some time if it is not the last retry time
            Uninterruptibles.sleepUninterruptibly(1000, TimeUnit.MILLISECONDS);
        }

        // throw the last exception if all retries failed
        Preconditions.checkState(lastException != null);
        String errorMsg = lastException.getMessage();
        if (lastException instanceof NullPointerException) {
            errorMsg = ExceptionUtils.getStackTrace(lastException);
        }
        // field ERROR_MESSAGE in information_schema.task_runs length is 65535
        errorMsg = errorMsg.length() > MAX_FIELD_VARCHAR_LENGTH ?
                errorMsg.substring(0, MAX_FIELD_VARCHAR_LENGTH) : errorMsg;
        throw new DmlException("Refresh materialized view %s failed after retrying %s times(try-lock %s times), error-msg : " +
                "%s", lastException, this.materializedView.getName(), refreshFailedTimes, lockFailedTimes, errorMsg);
    }

    private static int getMaxRefreshMaterializedViewRetryNum(ConnectContext currConnectCtx) {
        int maxRefreshMaterializedViewRetryNum = 1;
        if (currConnectCtx != null && currConnectCtx.getSessionVariable() != null) {
            maxRefreshMaterializedViewRetryNum =
                    currConnectCtx.getSessionVariable().getQueryDebugOptions().getMaxRefreshMaterializedViewRetryNum();
            if (maxRefreshMaterializedViewRetryNum <= 0) {
                maxRefreshMaterializedViewRetryNum = 1;
            }
        }
        maxRefreshMaterializedViewRetryNum = Math.max(Config.max_mv_refresh_failure_retry_times,
                maxRefreshMaterializedViewRetryNum);
        return maxRefreshMaterializedViewRetryNum;
    }

    private RefreshJobStatus doRefreshMaterializedView(TaskRunContext context,
                                                       IMaterializedViewMetricsEntity mvEntity) throws Exception {
        ///// 1. check to refresh partition names of materialized view
        // check and sync partitions between materialized view and base tables
        if (!syncAndCheckPartitions(context, mvEntity)) {
            throw new DmlException(String.format("materialized view %s refresh task failed: sync partition failed",
                    materializedView.getName()));
        }

        Set<String> mvToRefreshedPartitions = checkMvToRefreshedPartitions(context);
        if (Objects.isNull(mvToRefreshedPartitions) || mvToRefreshedPartitions.isEmpty()) {
            return RefreshJobStatus.EMPTY;
        }

        // Get to refreshed base table partition infos.
        Map<Table, Set<String>> refTableRefreshPartitions = getRefTableRefreshPartitions(mvToRefreshedPartitions);
        Map<String, Set<String>> refTablePartitionNames = refTableRefreshPartitions.entrySet().stream()
                .collect(Collectors.toMap(x -> x.getKey().getName(), Map.Entry::getValue));
        LOG.debug("materialized view:{} source partitions :{}",
                materializedView.getName(), refTableRefreshPartitions);
        // add a message into information_schema
        logMvToRefreshInfoIntoTaskRun(mvToRefreshedPartitions, refTablePartitionNames);

        ///// 2. execute the ExecPlan of insert stmt
        InsertStmt insertStmt = prepareRefreshPlan(mvToRefreshedPartitions, refTablePartitionNames);
        refreshMaterializedView(mvContext, mvContext.getExecPlan(), insertStmt);

        ///// 3. insert execute successfully, update the meta of materialized view according to ExecPlan
        updateMeta(mvToRefreshedPartitions, mvContext.getExecPlan(), refTableRefreshPartitions);

        return RefreshJobStatus.SUCCESS;
    }

    /**
     * Prepare the statement and plan for mv refreshing, considering the partitions of ref table
     */
    private InsertStmt prepareRefreshPlan(Set<String> mvToRefreshedPartitions,
                                          Map<String, Set<String>> refTablePartitionNames) throws AnalysisException {
        // 1. Prepare context
        ConnectContext ctx = mvContext.getCtx();
        ctx.getAuditEventBuilder().reset();
        ctx.getAuditEventBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setClientIp(mvContext.getRemoteIp())
                .setUser(ctx.getQualifiedUser())
                .setDb(ctx.getDatabase());
        ctx.setThreadLocalInfo();

        // 2. Prepare variables
        changeDefaultConnectContextIfNeeded(ctx);

        // 3. AST
        InsertStmt insertStmt = generateInsertAst(mvToRefreshedPartitions, materializedView, ctx);

        // 4. Analyze and prepare partition
        Map<String, Database> dbs = AnalyzerUtils.collectAllDatabase(ctx, insertStmt);
        ExecPlan execPlan = null;
        if (!StatementPlanner.tryLock(dbs, Config.mv_refresh_try_lock_timeout_ms, TimeUnit.MILLISECONDS)) {
            throw new LockTimeoutException("Failed to lock databases: " + Joiner.on(",").join(dbs.values().stream()
                    .map(Database::getFullName).collect(Collectors.toList())));
        }

        MVPCTRefreshPlanBuilder planBuilder = new MVPCTRefreshPlanBuilder(materializedView, mvContext);
        try {
            // 4. Analyze and prepare partition & Rebuild insert statement by
            // considering to-refresh partitions of ref tables/ mv
            insertStmt = planBuilder.analyzeAndBuildInsertPlan(insertStmt, refTablePartitionNames, ctx);
            // Must set execution id before StatementPlanner.plan
            ctx.setExecutionId(UUIDUtil.toTUniqueId(ctx.getQueryId()));

            // 5. generate insert stmt's exec plan, make thread local ctx existed
            try (ConnectContext.ScopeGuard guard = ctx.bindScope()) {
                execPlan = StatementPlanner.planInsertStmt(dbs, insertStmt, ctx);
            }
        } catch (Throwable e) {
            LOG.warn("prepareRefreshPlan for mv {} failed", materializedView.getName(), e);
            throw e;
        } finally {
            StatementPlanner.unLock(dbs);
        }

        QueryDebugOptions debugOptions = ctx.getSessionVariable().getQueryDebugOptions();
        // log the final mv refresh plan for each refresh for better trace and debug
        if (LOG.isDebugEnabled() || debugOptions.isEnableQueryTraceLog()) {
            LOG.info("MV Refresh Final Plan" +
                            "\nMV: {}" +
                            "\nMV PartitionsToRefresh: {}" +
                            "\nBase PartitionsToScan: {}" +
                            "\nInsert Plan:\n{}",
                    materializedView.getName(),
                    String.join(",", mvToRefreshedPartitions), refTablePartitionNames,
                    execPlan != null ? execPlan.getExplainString(StatementBase.ExplainLevel.VERBOSE) : "");
        } else {
            LOG.info("MV Refresh Final Plan, mv: {}, MV PartitionsToRefresh: {}, Base PartitionsToScan: {}",
                    materializedView.getName(), String.join(",", mvToRefreshedPartitions), refTablePartitionNames);
        }
        mvContext.setExecPlan(execPlan);

        return insertStmt;
    }

    /**
     * Change default connect context when for mv refresh this is because:
     * - MV Refresh may take much resource to load base tables' data into the final materialized view.
     * - Those changes are set by default and also able to be changed by users for their needs.
     *
     * @param mvConnectCtx
     */
    private void changeDefaultConnectContextIfNeeded(ConnectContext mvConnectCtx) {
        // add resource group if resource group is enabled
        TableProperty mvProperty = materializedView.getTableProperty();
        SessionVariable mvSessionVariable = mvConnectCtx.getSessionVariable();
        if (mvSessionVariable.isEnableResourceGroup()) {
            String rg = mvProperty.getResourceGroup();
            if (rg == null || rg.isEmpty()) {
                rg = ResourceGroup.DEFAULT_MV_RESOURCE_GROUP_NAME;
            }
            mvSessionVariable.setResourceGroup(rg);
        }

        // enable spill by default for mv if spill is not set by default and
        // `session.enable_spill` session variable is not set.
        if (Config.enable_materialized_view_spill &&
                !mvSessionVariable.getEnableSpill() &&
                !mvProperty.getProperties().containsKey(MV_SESSION_ENABLE_SPILL)) {
            mvSessionVariable.setEnableSpill(true);
        }

        // change `query_timeout` to 1 hour by default for better user experience.
        if (!mvProperty.getProperties().containsKey(MV_SESSION_TIMEOUT)) {
            mvSessionVariable.setQueryTimeoutS(MV_DEFAULT_QUERY_TIMEOUT);
        }
    }

    private void postProcess() {
        mvContext.ctx.getSessionVariable().setTransactionVisibleWaitTimeout(oldTransactionVisibleWaitTimeout);
    }

    public MVTaskRunExtraMessage getMVTaskRunExtraMessage() {
        if (this.mvContext.status == null) {
            return null;
        }
        return this.mvContext.status.getMvTaskRunExtraMessage();
    }

    @VisibleForTesting
    public void filterPartitionByRefreshNumber(Set<String> partitionsToRefresh, MaterializedView materializedView) {
        int partitionRefreshNumber = materializedView.getTableProperty().getPartitionRefreshNumber();
        if (partitionRefreshNumber <= 0) {
            return;
        }
        Map<String, Range<PartitionKey>> rangePartitionMap = materializedView.getRangePartitionMap();
        if (partitionRefreshNumber >= rangePartitionMap.size()) {
            return;
        }
        Map<String, Range<PartitionKey>> mappedPartitionsToRefresh = Maps.newHashMap();
        for (String partitionName : partitionsToRefresh) {
            mappedPartitionsToRefresh.put(partitionName, rangePartitionMap.get(partitionName));
        }
        LinkedHashMap<String, Range<PartitionKey>> sortedPartition = mappedPartitionsToRefresh.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(RangeUtils.RANGE_COMPARATOR))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

        Iterator<String> partitionNameIter = sortedPartition.keySet().iterator();
        for (int i = 0; i < partitionRefreshNumber; i++) {
            if (partitionNameIter.hasNext()) {
                partitionNameIter.next();
            }
        }
        String nextPartitionStart = null;
        String endPartitionName = null;
        if (partitionNameIter.hasNext()) {
            String startPartitionName = partitionNameIter.next();
            Range<PartitionKey> partitionKeyRange = mappedPartitionsToRefresh.get(startPartitionName);
            nextPartitionStart = AnalyzerUtils.parseLiteralExprToDateString(partitionKeyRange.lowerEndpoint(), 0);
            endPartitionName = startPartitionName;
            partitionsToRefresh.remove(endPartitionName);
        }
        while (partitionNameIter.hasNext()) {
            endPartitionName = partitionNameIter.next();
            partitionsToRefresh.remove(endPartitionName);
        }

        mvContext.setNextPartitionStart(nextPartitionStart);

        if (endPartitionName != null) {
            PartitionKey upperEndpoint = mappedPartitionsToRefresh.get(endPartitionName).upperEndpoint();
            mvContext.setNextPartitionEnd(AnalyzerUtils.parseLiteralExprToDateString(upperEndpoint, 0));
        } else {
            // partitionNameIter has just been traversed, and endPartitionName is not updated
            // will cause endPartitionName == null
            mvContext.setNextPartitionEnd(null);
        }
    }

    private void generateNextTaskRun() {
        TaskManager taskManager = GlobalStateMgr.getCurrentState().getTaskManager();
        Map<String, String> properties = mvContext.getProperties();
        long mvId = Long.parseLong(properties.get(MV_ID));
        String taskName = TaskBuilder.getMvTaskName(mvId);
        Map<String, String> newProperties = Maps.newHashMap();
        for (Map.Entry<String, String> proEntry : properties.entrySet()) {
            if (proEntry.getValue() != null) {
                newProperties.put(proEntry.getKey(), proEntry.getValue());
            }
        }
        newProperties.put(TaskRun.PARTITION_START, mvContext.getNextPartitionStart());
        newProperties.put(TaskRun.PARTITION_END, mvContext.getNextPartitionEnd());
        // Partition refreshing task run should have the HIGHEST priority, and be scheduled before other tasks
        // Otherwise this round of partition refreshing would be staved and never got finished
        ExecuteOption option = new ExecuteOption(Constants.TaskRunPriority.HIGHEST.value(), true, newProperties);
        taskManager.executeTask(taskName, option);
        LOG.info("[MV] Generate a task to refresh next batches of partitions for MV {}-{}, start={}, end={}",
                materializedView.getName(), materializedView.getId(),
                mvContext.getNextPartitionStart(), mvContext.getNextPartitionEnd());
    }

    private void refreshExternalTable(TaskRunContext context) {
        List<BaseTableInfo> baseTableInfos = materializedView.getBaseTableInfos();
        for (BaseTableInfo baseTableInfo : baseTableInfos) {
            Database db = baseTableInfo.getDb();
            if (db == null) {
                LOG.warn("database {} do not exist when refreshing materialized view:{}",
                        baseTableInfo.getDbInfoStr(), materializedView.getName());
                throw new DmlException("database " + baseTableInfo.getDbInfoStr() + " do not exist.");
            }

            Table table = baseTableInfo.getTable();
            if (table == null) {
                LOG.warn("table {} do not exist when refreshing materialized view:{}",
                        baseTableInfo.getTableInfoStr(), materializedView.getName());
                materializedView.setInactiveAndReason(
                        MaterializedViewExceptions.inactiveReasonForBaseTableNotExists(baseTableInfo.getTableName()));
                throw new DmlException("Materialized view base table: %s not exist.", baseTableInfo.getTableInfoStr());
            }

            if (!table.isNativeTableOrMaterializedView() && !table.isHiveView()) {
                context.getCtx().getGlobalStateMgr().getMetadataMgr().refreshTable(baseTableInfo.getCatalogName(),
                        baseTableInfo.getDbName(), table, Lists.newArrayList(), true);
            }
        }
    }

    /**
     * After materialized view is refreshed, update materialized view's meta info to record history refreshes.
     *
     * @param refTableAndPartitionNames : refreshed base table and its partition names mapping.
     */
    private void updateMeta(Set<String> mvRefreshedPartitions,
                            ExecPlan execPlan,
                            Map<Table, Set<String>> refTableAndPartitionNames) {
        // update the meta if succeed
        if (!database.writeLockAndCheckExist()) {
            throw new DmlException("update meta failed. database:" + database.getFullName() + " not exist");
        }
        try {
            // check
            Table mv = database.getTable(materializedView.getId());
            if (mv == null) {
                throw new DmlException(
                        "update meta failed. materialized view:" + materializedView.getName() + " not exist");
            }

            // check
            if (mvRefreshedPartitions == null || refTableAndPartitionNames == null) {
                return;
            }

            // NOTE: For each task run, ref-base table's incremental partition and all non-ref base tables' partitions
            // are refreshed, so we need record it into materialized view.
            // NOTE: We don't use the pruned partition infos from ExecPlan because the optimized partition infos are not
            // exact to describe which partitions are refreshed.
            Map<Table, Set<String>> baseTableAndPartitionNames = Maps.newHashMap();
            for (Map.Entry<Table, Set<String>> e : refTableAndPartitionNames.entrySet()) {
                Set<String> realPartitionNames =
                        e.getValue().stream()
                                .flatMap(name -> convertMVPartitionNameToRealPartitionName(e.getKey(), name).stream())
                                .collect(Collectors.toSet());
                baseTableAndPartitionNames.put(e.getKey(), realPartitionNames);
            }
            Map<Table, Set<String>> nonRefTableAndPartitionNames = getNonRefTableRefreshPartitions();
            if (!nonRefTableAndPartitionNames.isEmpty()) {
                baseTableAndPartitionNames.putAll(nonRefTableAndPartitionNames);
            }

            MaterializedView.MvRefreshScheme mvRefreshScheme = materializedView.getRefreshScheme();
            MaterializedView.AsyncRefreshContext refreshContext = mvRefreshScheme.getAsyncRefreshContext();

            // update materialized view partition to ref base table partition names meta
            updateAssociatedPartitionMeta(refreshContext, mvRefreshedPartitions, refTableAndPartitionNames);

            Map<Long, Map<String, MaterializedView.BasePartitionInfo>> changedOlapTablePartitionInfos =
                    getSelectedPartitionInfosOfOlapTable(baseTableAndPartitionNames);
            Map<BaseTableInfo, Map<String, MaterializedView.BasePartitionInfo>> changedExternalTablePartitionInfos
                    = getSelectedPartitionInfosOfExternalTable(baseTableAndPartitionNames);
            Preconditions.checkState(changedOlapTablePartitionInfos.size() + changedExternalTablePartitionInfos.size()
                    <= baseTableAndPartitionNames.size());
            updateMetaForOlapTable(refreshContext, changedOlapTablePartitionInfos);
            updateMetaForExternalTable(refreshContext, changedExternalTablePartitionInfos);

            // add message into information_schema
            if (this.getMVTaskRunExtraMessage() != null) {
                try {
                    MVTaskRunExtraMessage extraMessage = getMVTaskRunExtraMessage();
                    Map<String, Set<String>> baseTableRefreshedPartitionsByExecPlan =
                            getBaseTableRefreshedPartitionsByExecPlan(execPlan);
                    extraMessage.setBasePartitionsToRefreshMap(baseTableRefreshedPartitionsByExecPlan);
                } catch (Exception e) {
                    // just log warn and no throw exceptions for updating task runs message.
                    LOG.warn("update task run messages failed:", e);
                }
            }
        } catch (Exception e) {
            LOG.warn("update final meta failed after mv refreshed:", e);
            throw e;
        } finally {
            database.writeUnlock();
        }
    }

    private void updateAssociatedPartitionMeta(MaterializedView.AsyncRefreshContext refreshContext,
                                               Set<String> mvRefreshedPartitions,
                                               Map<Table, Set<String>> refTableAndPartitionNames) {
        Map<String, Set<String>> mvToBaseNameRef = mvContext.getMvRefBaseTableIntersectedPartitions();
        if (mvToBaseNameRef != null) {
            try {
                Table refBaseTable = refTableAndPartitionNames.keySet().iterator().next();
                Map<String, Set<String>> mvPartitionNameRefBaseTablePartitionMap =
                        refreshContext.getMvPartitionNameRefBaseTablePartitionMap();
                for (String mvRefreshedPartition : mvRefreshedPartitions) {
                    Set<String> realBaseTableAssociatedPartitions = Sets.newHashSet();
                    for (String refBaseTableAssociatedPartition : mvToBaseNameRef.get(mvRefreshedPartition)) {
                        realBaseTableAssociatedPartitions.addAll(
                                convertMVPartitionNameToRealPartitionName(refBaseTable,
                                        refBaseTableAssociatedPartition));
                    }
                    mvPartitionNameRefBaseTablePartitionMap
                            .put(mvRefreshedPartition, realBaseTableAssociatedPartitions);
                }

            } catch (Exception e) {
                LOG.warn("Update materialized view {} with the associated ref base table partitions failed: ",
                        materializedView.getName(), e);
            }
        }
    }

    private void updateMetaForOlapTable(MaterializedView.AsyncRefreshContext refreshContext,
                                        Map<Long, Map<String, MaterializedView.BasePartitionInfo>> changedTablePartitionInfos) {
        Map<Long, Map<String, MaterializedView.BasePartitionInfo>> currentVersionMap =
                refreshContext.getBaseTableVisibleVersionMap();
        Table partitionTable = null;
        if (mvContext.hasNextBatchPartition()) {
            Pair<Table, Column> partitionTableAndColumn = getRefBaseTableAndPartitionColumn(snapshotBaseTables);
            partitionTable = partitionTableAndColumn.first;
        }
        // update version map of materialized view
        for (Map.Entry<Long, Map<String, MaterializedView.BasePartitionInfo>> tableEntry
                : changedTablePartitionInfos.entrySet()) {
            Long tableId = tableEntry.getKey();
            if (partitionTable != null && tableId != partitionTable.getId()) {
                continue;
            }
            currentVersionMap.computeIfAbsent(tableId, (v) -> Maps.newConcurrentMap());
            Map<String, MaterializedView.BasePartitionInfo> currentTablePartitionInfo =
                    currentVersionMap.get(tableId);
            Map<String, MaterializedView.BasePartitionInfo> partitionInfoMap = tableEntry.getValue();
            LOG.info("Update materialized view {} meta for base table {} with partitions info: {}, old partition infos:{}",
                    materializedView.getName(), tableId, partitionInfoMap, currentTablePartitionInfo);
            currentTablePartitionInfo.putAll(partitionInfoMap);

            // remove partition info of not-exist partition for snapshot table from version map
            Table snapshotTable = Preconditions.checkNotNull(snapshotBaseTables.get(tableId),
                    "base table not found: " + tableId).second;
            if (snapshotTable.isOlapOrCloudNativeTable()) {
                OlapTable snapshotOlapTable = (OlapTable) snapshotTable;
                currentTablePartitionInfo.keySet().removeIf(partitionName ->
                        !snapshotOlapTable.getVisiblePartitionNames().contains(partitionName));
            }
        }
        if (!changedTablePartitionInfos.isEmpty()) {
            ChangeMaterializedViewRefreshSchemeLog changeRefreshSchemeLog =
                    new ChangeMaterializedViewRefreshSchemeLog(materializedView);
            long maxChangedTableRefreshTime =
                    MvUtils.getMaxTablePartitionInfoRefreshTime(changedTablePartitionInfos.values());
            materializedView.getRefreshScheme().setLastRefreshTime(maxChangedTableRefreshTime);
            GlobalStateMgr.getCurrentState().getEditLog().logMvChangeRefreshScheme(changeRefreshSchemeLog);
        }
    }

    private void updateMetaForExternalTable(
            MaterializedView.AsyncRefreshContext refreshContext,
            Map<BaseTableInfo, Map<String, MaterializedView.BasePartitionInfo>> changedTablePartitionInfos) {
        Map<BaseTableInfo, Map<String, MaterializedView.BasePartitionInfo>> currentVersionMap =
                refreshContext.getBaseTableInfoVisibleVersionMap();
        BaseTableInfo partitionTableInfo = null;
        if (mvContext.hasNextBatchPartition()) {
            Pair<Table, Column> partitionTableAndColumn = getRefBaseTableAndPartitionColumn(snapshotBaseTables);
            partitionTableInfo =
                    Preconditions.checkNotNull(snapshotBaseTables.get(partitionTableAndColumn.first.getId()),
                            "base table not found: " + partitionTableAndColumn.first.getId())
                            .first;
        }
        // update version map of materialized view
        for (Map.Entry<BaseTableInfo, Map<String, MaterializedView.BasePartitionInfo>> tableEntry
                : changedTablePartitionInfos.entrySet()) {
            BaseTableInfo baseTableInfo = tableEntry.getKey();
            if (partitionTableInfo != null && !partitionTableInfo.equals(baseTableInfo)) {
                continue;
            }
            currentVersionMap.computeIfAbsent(baseTableInfo, (v) -> Maps.newConcurrentMap());
            Map<String, MaterializedView.BasePartitionInfo> currentTablePartitionInfo =
                    currentVersionMap.get(baseTableInfo);
            Map<String, MaterializedView.BasePartitionInfo> partitionInfoMap = tableEntry.getValue();
            LOG.info("Update materialized view {} meta for external base table {} with partitions info: {}, " +
                            "old partition infos:{}", materializedView.getName(), baseTableInfo.getTableId(),
                    partitionInfoMap, currentTablePartitionInfo);
            // overwrite old partition names
            currentTablePartitionInfo.putAll(partitionInfoMap);

            // remove partition info of not-exist partition for snapshot table from version map
            Set<String> partitionNames =
                    Sets.newHashSet(PartitionUtil.getPartitionNames(baseTableInfo.getTableChecked()));
            currentTablePartitionInfo.keySet().removeIf(partitionName -> !partitionNames.contains(partitionName));
        }
        if (!changedTablePartitionInfos.isEmpty()) {
            ChangeMaterializedViewRefreshSchemeLog changeRefreshSchemeLog =
                    new ChangeMaterializedViewRefreshSchemeLog(materializedView);
            long maxChangedTableRefreshTime =
                    MvUtils.getMaxTablePartitionInfoRefreshTime(changedTablePartitionInfos.values());
            materializedView.getRefreshScheme().setLastRefreshTime(maxChangedTableRefreshTime);
            GlobalStateMgr.getCurrentState().getEditLog().logMvChangeRefreshScheme(changeRefreshSchemeLog);
        }
    }

    private void prepare(TaskRunContext context) {
        Map<String, String> properties = context.getProperties();
        // NOTE: mvId is set in Task's properties when creating
        long mvId = Long.parseLong(properties.get(MV_ID));
        database = GlobalStateMgr.getCurrentState().getDb(context.ctx.getDatabase());
        if (database == null) {
            LOG.warn("database {} do not exist when refreshing materialized view:{}", context.ctx.getDatabase(), mvId);
            throw new DmlException("database " + context.ctx.getDatabase() + " do not exist.");
        }
        Table table = database.getTable(mvId);
        if (table == null) {
            LOG.warn("materialized view:{} in database:{} do not exist when refreshing", mvId,
                    context.ctx.getDatabase());
            throw new DmlException("database " + context.ctx.getDatabase() + " do not exist.");
        }
        materializedView = (MaterializedView) table;

        // try to activate the mv before refresh
        if (!materializedView.isActive()) {
            MVActiveChecker.tryToActivate(materializedView);
            LOG.info("Activated the MV before refreshing: {}", materializedView.getName());
        }

        IMaterializedViewMetricsEntity mvEntity =
                MaterializedViewMetricsRegistry.getInstance().getMetricsEntity(materializedView.getMvId());
        if (!materializedView.isActive()) {
            String errorMsg = String.format("Materialized view: %s/%d is not active due to %s.",
                    materializedView.getName(), mvId, materializedView.getInactiveReason());
            LOG.warn(errorMsg);
            mvEntity.increaseRefreshJobStatus(RefreshJobStatus.FAILED);
            throw new DmlException(errorMsg);
        }
        // wait util transaction is visible for mv refresh task
        // because mv will update base tables' visible version after insert, the mv's visible version
        // should keep up with the base tables, or it will return outdated result.
        oldTransactionVisibleWaitTimeout = context.ctx.getSessionVariable().getTransactionVisibleWaitTimeout();
        context.ctx.getSessionVariable().setTransactionVisibleWaitTimeout(Long.MAX_VALUE / 1000);
        mvContext = new MvTaskRunContext(context);
    }

    /**
     * Sync base table's partition infos to be used later.
     */
    private void syncPartitions(TaskRunContext context) {
        snapshotBaseTables = collectBaseTables(materializedView);
        PartitionInfo partitionInfo = materializedView.getPartitionInfo();

        if (!(partitionInfo instanceof SinglePartitionInfo)) {
            Pair<Table, Column> partitionTableAndColumn = getRefBaseTableAndPartitionColumn(snapshotBaseTables);
            mvContext.setRefBaseTable(partitionTableAndColumn.first);
            mvContext.setRefBaseTablePartitionColumn(partitionTableAndColumn.second);
        }
        int partitionTTLNumber = materializedView.getTableProperty().getPartitionTTLNumber();
        mvContext.setPartitionTTLNumber(partitionTTLNumber);

        if (partitionInfo instanceof ExpressionRangePartitionInfo) {
            syncPartitionsForExpr(context);
        }
    }

    /**
     * @param tables : base tables of the materialized view
     * @return : return the ref base table and column that materialized view's partition column
     * derives from if it exists, otherwise return null.
     */
    private Pair<Table, Column> getRefBaseTableAndPartitionColumn(
            Map<Long, Pair<BaseTableInfo, Table>> tables) {
        SlotRef slotRef = MaterializedView.getRefBaseTablePartitionSlotRef(materializedView);
        for (Pair<BaseTableInfo, Table> tableInfo : tables.values()) {
            BaseTableInfo baseTableInfo = tableInfo.first;
            Table table = tableInfo.second;
            if (slotRef.getTblNameWithoutAnalyzed().getTbl().equals(baseTableInfo.getTableName())) {
                return Pair.create(table, table.getColumn(slotRef.getColumnName()));
            }
        }
        return Pair.create(null, null);
    }

    private void syncPartitionsForExpr(TaskRunContext context) {
        Expr partitionExpr = materializedView.getFirstPartitionRefTableExpr();
        Pair<Table, Column> partitionTableAndColumn = materializedView.getBaseTableAndPartitionColumn();
        Table refBaseTable = partitionTableAndColumn.first;
        Preconditions.checkNotNull(refBaseTable);
        Column refBaseTablePartitionColumn = partitionTableAndColumn.second;
        Preconditions.checkNotNull(refBaseTablePartitionColumn);

        RangePartitionDiff rangePartitionDiff = new RangePartitionDiff();

        int partitionTTLNumber = materializedView.getTableProperty().getPartitionTTLNumber();
        mvContext.setPartitionTTLNumber(partitionTTLNumber);
        Map<String, Range<PartitionKey>> mvPartitionMap = materializedView.getRangePartitionMap();
        if (!database.tryReadLock(Config.mv_refresh_try_lock_timeout_ms, TimeUnit.MILLISECONDS)) {
            throw new LockTimeoutException("Failed to lock database: " + database.getFullName());
        }

        Map<String, Range<PartitionKey>> mvRangePartitionMap = materializedView.getRangePartitionMap();
        Map<String, Range<PartitionKey>> refBaseTablePartitionMap;
        Map<String, Set<String>> refBaseTableMVPartitionMap = Maps.newHashMap();
        try {
            // Collect the ref base table's partition range map.
            refBaseTablePartitionMap = PartitionUtil.getPartitionKeyRange(
                    refBaseTable, refBaseTablePartitionColumn, partitionExpr);

            // To solve multi partition columns' problem of external table, record the mv partition name to all the same
            // partition names map here.
            if (!refBaseTable.isNativeTableOrMaterializedView()) {
                refBaseTableMVPartitionMap = PartitionUtil.getMVPartitionNameMapOfExternalTable(refBaseTable,
                        refBaseTablePartitionColumn, PartitionUtil.getPartitionNames(refBaseTable));
            }

            Column partitionColumn =
                    ((RangePartitionInfo) materializedView.getPartitionInfo()).getPartitionColumns().get(0);
            PartitionDiffer differ = PartitionDiffer.build(materializedView, context);
            rangePartitionDiff = PartitionUtil.getPartitionDiff(
                    partitionExpr, partitionColumn, refBaseTablePartitionMap, mvPartitionMap, differ);
        } catch (UserException e) {
            LOG.warn("Materialized view compute partition difference with base table failed.", e);
            return;
        } finally {
            database.readUnlock();
        }

        Map<String, Range<PartitionKey>> deletes = rangePartitionDiff.getDeletes();

        // Delete old partitions and then add new partitions because the old and new partitions may overlap
        for (Map.Entry<String, Range<PartitionKey>> deleteEntry : deletes.entrySet()) {
            String mvPartitionName = deleteEntry.getKey();
            dropPartition(database, materializedView, mvPartitionName);
        }
        LOG.info("The process of synchronizing materialized view [{}] delete partitions range [{}]",
                materializedView.getName(), deletes);

        // Create new added materialized views' ranges
        Map<String, String> partitionProperties = getPartitionProperties(materializedView);
        DistributionDesc distributionDesc = getDistributionDesc(materializedView);
        Map<String, Range<PartitionKey>> adds = rangePartitionDiff.getAdds();

        addPartitions(database, materializedView, adds, partitionProperties, distributionDesc);
        for (Map.Entry<String, Range<PartitionKey>> addEntry : adds.entrySet()) {
            String mvPartitionName = addEntry.getKey();
            mvRangePartitionMap.put(mvPartitionName, addEntry.getValue());
        }
        LOG.info("The process of synchronizing materialized view [{}] add partitions range [{}]",
                materializedView.getName(), adds);

        // used to get partitions to refresh
        Map<String, Set<String>> baseToMvNameRef = SyncPartitionUtils
                .getIntersectedPartitions(refBaseTablePartitionMap, mvRangePartitionMap);
        Map<String, Set<String>> mvToBaseNameRef = SyncPartitionUtils
                .getIntersectedPartitions(mvRangePartitionMap, refBaseTablePartitionMap);

        mvContext.setRefBaseTableMVIntersectedPartitions(baseToMvNameRef);
        mvContext.setMvRefBaseTableIntersectedPartitions(mvToBaseNameRef);
        mvContext.setRefBaseTableRangePartitionMap(refBaseTablePartitionMap);
        mvContext.setExternalRefBaseTableMVPartitionMap(refBaseTableMVPartitionMap);
    }

    private boolean needToRefreshTable(Table table) {
        return CollectionUtils.isNotEmpty(materializedView.getUpdatedPartitionNamesOfTable(table, false));
    }

    private static boolean supportPartitionRefresh(Table table) {
        if (table.getType() == Table.TableType.VIEW) {
            return false;
        }
        return ConnectorPartitionTraits.build(table).supportPartitionRefresh();
    }

    /**
     * Whether non-partitioned materialized view needs to be refreshed or not, it needs refresh when:
     * - its base table is not supported refresh by partition.
     * - its base table has updated.
     */
    private boolean isNonPartitionedMVNeedToRefresh() {
        for (Pair<BaseTableInfo, Table> tablePair : snapshotBaseTables.values()) {
            Table snapshotTable = tablePair.second;
            if (!supportPartitionRefresh(snapshotTable)) {
                return true;
            }
            if (needToRefreshTable(snapshotTable)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether partitioned materialized view needs to be refreshed or not base on the non-ref base tables, it needs refresh when:
     * - its non-ref base table except un-supported base table has updated.
     */
    private boolean isPartitionedMVNeedToRefreshBaseOnNonRefTables(Table partitionTable) {
        for (Pair<BaseTableInfo, Table> tablePair : snapshotBaseTables.values()) {
            Table snapshotTable = tablePair.second;
            if (snapshotTable.getId() == partitionTable.getId()) {
                continue;
            }
            if (!supportPartitionRefresh(snapshotTable)) {
                continue;
            }
            if (needToRefreshTable(snapshotTable)) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    public Set<String> getPartitionsToRefreshForMaterializedView(Map<String, String> properties)
            throws AnalysisException {
        String start = properties.get(TaskRun.PARTITION_START);
        String end = properties.get(TaskRun.PARTITION_END);
        boolean force = Boolean.parseBoolean(properties.get(TaskRun.FORCE));
        PartitionInfo partitionInfo = materializedView.getPartitionInfo();
        Set<String> needRefreshMvPartitionNames = getPartitionsToRefreshForMaterializedView(partitionInfo,
                start, end, force);
        // update stats
        if (this.getMVTaskRunExtraMessage() != null) {
            MVTaskRunExtraMessage extraMessage = this.getMVTaskRunExtraMessage();
            extraMessage.setForceRefresh(force);
            extraMessage.setPartitionStart(start);
            extraMessage.setPartitionEnd(end);
        }
        return needRefreshMvPartitionNames;
    }

    /**
     * @param mvPartitionInfo : materialized view's partition info
     * @param start           : materialized view's refresh start in this task run
     * @param end             : materialized view's refresh end in this task run
     * @param force           : whether this task run is force or not
     * @return
     * @throws AnalysisException
     */
    private Set<String> getPartitionsToRefreshForMaterializedView(PartitionInfo mvPartitionInfo,
                                                                  String start,
                                                                  String end,
                                                                  boolean force) throws AnalysisException {
        int partitionTTLNumber = mvContext.getPartitionTTLNumber();

        // Force refresh
        if (force && start == null && end == null) {
            if (mvPartitionInfo instanceof SinglePartitionInfo) {
                return materializedView.getVisiblePartitionNames();
            } else {
                return materializedView.getValidPartitionMap(partitionTTLNumber).keySet();
            }
        }

        Set<String> needRefreshMvPartitionNames = Sets.newHashSet();
        if (mvPartitionInfo instanceof SinglePartitionInfo) {
            // non-partitioned materialized view
            if (force || isNonPartitionedMVNeedToRefresh()) {
                return materializedView.getVisiblePartitionNames();
            }
        } else if (mvPartitionInfo instanceof ExpressionRangePartitionInfo) {
            // range partitioned materialized views
            Expr partitionExpr = MaterializedView.getPartitionExpr(materializedView);
            Table refBaseTable = mvContext.getRefBaseTable();

            boolean isAutoRefresh = mvContext.getTaskType().isAutoRefresh();
            Set<String> mvRangePartitionNames = SyncPartitionUtils.getPartitionNamesByRangeWithPartitionLimit(
                    materializedView, start, end, partitionTTLNumber, isAutoRefresh);
            LOG.info("Get partition names by range with partition limit, start: {}, end: {}, partitionTTLNumber: {}," +
                            " isAutoRefresh: {}, mvRangePartitionNames: {}",
                    start, end, partitionTTLNumber, isAutoRefresh, mvRangePartitionNames);

            // check non-ref base tables
            if (isPartitionedMVNeedToRefreshBaseOnNonRefTables(refBaseTable)) {
                if (start == null && end == null) {
                    // if non partition table changed, should refresh all partitions of materialized view
                    return mvRangePartitionNames;
                } else {
                    // If the user specifies the start and end ranges, and the non-partitioned table still changes,
                    // it should be refreshed according to the user-specified range, not all partitions.
                    return getMVPartitionNamesToRefreshByRangePartitionNamesAndForce(refBaseTable,
                            mvRangePartitionNames, true);
                }
            }

            // check the ref base table
            if (partitionExpr instanceof SlotRef) {
                return getMVPartitionNamesToRefreshByRangePartitionNamesAndForce(refBaseTable, mvRangePartitionNames,
                        force);
            } else if (partitionExpr instanceof FunctionCallExpr) {
                needRefreshMvPartitionNames = getMVPartitionNamesToRefreshByRangePartitionNamesAndForce(refBaseTable,
                        mvRangePartitionNames, force);

                Set<String> baseChangedPartitionNames =
                        getBasePartitionNamesByMVPartitionNames(needRefreshMvPartitionNames);
                // because the relation of partitions between materialized view and base partition table is n : m,
                // should calculate the candidate partitions recursively.
                LOG.debug("Start calcPotentialRefreshPartition, needRefreshMvPartitionNames: {}," +
                        " baseChangedPartitionNames: {}", needRefreshMvPartitionNames, baseChangedPartitionNames);
                SyncPartitionUtils.calcPotentialRefreshPartition(needRefreshMvPartitionNames, baseChangedPartitionNames,
                        mvContext.getRefBaseTableMVIntersectedPartitions(),
                        mvContext.getMvRefBaseTableIntersectedPartitions());
                LOG.debug("Finish calcPotentialRefreshPartition, needRefreshMvPartitionNames: {}," +
                        " baseChangedPartitionNames: {}", needRefreshMvPartitionNames, baseChangedPartitionNames);
            }
        } else {
            throw new DmlException("unsupported partition info type:" + mvPartitionInfo.getClass().getName());
        }
        return needRefreshMvPartitionNames;
    }

    private Set<String> getMVPartitionNamesToRefreshByRangePartitionNamesAndForce(Table refBaseTable,
                                                                                  Set<String> mvRangePartitionNames,
                                                                                  boolean force) {
        if (force || !supportPartitionRefresh(refBaseTable)) {
            LOG.info("The ref base table {} is not supported partition refresh, refresh all " +
                    "partitions of materialized view: {}", refBaseTable.getName(), materializedView.getName());
            return Sets.newHashSet(mvRangePartitionNames);
        }

        // step1: check updated partition names in the ref base table and add it to the refresh candidate
        Set<String> updatePartitionNames = materializedView.getUpdatedPartitionNamesOfTable(refBaseTable, false);
        if (updatePartitionNames == null) {
            LOG.warn("Cannot find the updated partition info of ref base table {} of mv: {}",
                    refBaseTable.getName(), materializedView.getName());
            return mvRangePartitionNames;
        }

        // step2: fetch the corresponding materialized view partition names as the need to refresh partitions
        Set<String> result = getMVPartitionNamesByBasePartitionNames(updatePartitionNames);
        result.retainAll(mvRangePartitionNames);
        LOG.info("The ref base table {} has updated partitions: {}, the corresponding mv partitions to refresh: {}, " +
                        "mvRangePartitionNames: {}", refBaseTable.getName(), updatePartitionNames, result, mvRangePartitionNames);
        return result;
    }

    /**
     * @param basePartitionNames : ref base table partition names to check.
     * @return : Return mv corresponding partition names to the ref base table partition names.
     */
    private Set<String> getMVPartitionNamesByBasePartitionNames(Set<String> basePartitionNames) {
        Set<String> result = Sets.newHashSet();
        Map<String, Set<String>> refBaseTableMVPartitionMap = mvContext.getRefBaseTableMVIntersectedPartitions();
        for (String basePartitionName : basePartitionNames) {
            if (refBaseTableMVPartitionMap.containsKey(basePartitionName)) {
                result.addAll(refBaseTableMVPartitionMap.get(basePartitionName));
            } else {
                LOG.warn("Cannot find need refreshed ref base table partition from synced partition info: {}",
                        basePartitionName);
            }
        }
        return result;
    }

    /**
     * @param mvPartitionNames : the need to refresh materialized view partition names
     * @return : the corresponding ref base table partition names to the materialized view partition names
     */
    private Set<String> getBasePartitionNamesByMVPartitionNames(Set<String> mvPartitionNames) {
        Set<String> result = Sets.newHashSet();
        Map<String, Set<String>> mvRefBaseTablePartitionMap = mvContext.getMvRefBaseTableIntersectedPartitions();
        for (String mvPartitionName : mvPartitionNames) {
            if (mvRefBaseTablePartitionMap.containsKey(mvPartitionName)) {
                result.addAll(mvRefBaseTablePartitionMap.get(mvPartitionName));
            } else {
                LOG.warn("Cannot find need refreshed mv table partition from synced partition info: {}",
                        mvPartitionName);
            }
        }
        return result;
    }

    /**
     * Build an AST for insert stmt
     */
    private InsertStmt generateInsertAst(Set<String> materializedViewPartitions, MaterializedView materializedView,
                                         ConnectContext ctx) {
        // TODO: Support use mv when refreshing mv.
        ctx.getSessionVariable().setEnableMaterializedViewRewrite(false);
        String definition = mvContext.getDefinition();
        InsertStmt insertStmt =
                (InsertStmt) SqlParser.parse(definition, ctx.getSessionVariable()).get(0);
        // set target partitions
        insertStmt.setTargetPartitionNames(new PartitionNames(false, new ArrayList<>(materializedViewPartitions)));
        // insert overwrite mv must set system = true
        insertStmt.setSystem(true);
        // if materialized view has set sort keys, materialized view's output columns
        // may be different from the defined query's output.
        // so set materialized view's defined outputs as target columns.
        List<Integer> queryOutputIndexes = materializedView.getQueryOutputIndices();
        List<Column> baseSchema = materializedView.getBaseSchema();
        if (queryOutputIndexes != null && baseSchema.size() == queryOutputIndexes.size()) {
            List<String> targetColumnNames = queryOutputIndexes.stream()
                    .map(baseSchema::get)
                    .map(Column::getName)
                    .map(String::toLowerCase) // case insensitive
                    .collect(Collectors.toList());
            insertStmt.setTargetColumnNames(targetColumnNames);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Generate refresh materialized view {} insert-overwrite statement, " +
                            "materialized view's target partition names:{}, " +
                            "materialized view's target columns: {}, " +
                            "definition:{}",
                    materializedView.getName(),
                    Joiner.on(",").join(materializedViewPartitions),
                    insertStmt.getTargetColumnNames() == null ? "" :
                            Joiner.on(",").join(insertStmt.getTargetColumnNames()),
                    definition);
        }
        return insertStmt;
    }

    /**
     * Collect all deduplicated databases of the materialized view's base tables.
     * @param materializedView: the materialized view to check
     * @return: the databases of the materialized view's base tables, throw exception if the database do not exist.
     */
    List<Database> collectDatabases(MaterializedView materializedView) {
        Map<Long, Database> databaseMap = Maps.newHashMap();
        for (BaseTableInfo baseTableInfo : materializedView.getBaseTableInfos()) {
            Database db = baseTableInfo.getDb();
            if (db == null) {
                LOG.warn("database {} do not exist when refreshing materialized view:{}",
                        baseTableInfo.getDbInfoStr(), materializedView.getName());
                throw new DmlException("database " + baseTableInfo.getDbInfoStr() + " do not exist.");
            }
            databaseMap.put(db.getId(), db);
        }
        return Lists.newArrayList(databaseMap.values());
    }

    private boolean checkBaseTableSnapshotInfoChanged(BaseTableInfo baseTableInfo,
                                                      Table snapshotTable) {
        try {
            Table table = baseTableInfo.getTable();
            if (table == null) {
                return true;
            }

            if (snapshotTable.isOlapOrCloudNativeTable()) {
                OlapTable snapShotOlapTable = (OlapTable) snapshotTable;
                PartitionInfo snapshotPartitionInfo = snapShotOlapTable.getPartitionInfo();
                if (snapshotPartitionInfo instanceof SinglePartitionInfo) {
                    Set<String> partitionNames = ((OlapTable) table).getVisiblePartitionNames();
                    if (!snapShotOlapTable.getVisiblePartitionNames().equals(partitionNames)) {
                        // there is partition rename
                        return true;
                    }
                } else {
                    Map<String, Range<PartitionKey>> snapshotPartitionMap =
                            snapShotOlapTable.getRangePartitionMap();
                    Map<String, Range<PartitionKey>> currentPartitionMap =
                            ((OlapTable) table).getRangePartitionMap();
                    boolean changed =
                            SyncPartitionUtils.hasPartitionChange(snapshotPartitionMap, currentPartitionMap);
                    if (changed) {
                        return true;
                    }
                }
            } else if (ConnectorPartitionTraits.isSupported(snapshotTable.getType())) {
                if (snapshotTable.isUnPartitioned()) {
                    if (!table.isUnPartitioned()) {
                        return true;
                    }
                } else {
                    PartitionInfo mvPartitionInfo = materializedView.getPartitionInfo();
                    // do not need to check base partition table changed when mv is not partitioned
                    if (!(mvPartitionInfo instanceof ExpressionRangePartitionInfo)) {
                        return false;
                    }
                    Pair<Table, Column> partitionTableAndColumn =
                            getRefBaseTableAndPartitionColumn(snapshotBaseTables);
                    Column partitionColumn = partitionTableAndColumn.second;
                    // For Non-partition based base table, it's not necessary to check the partition changed.
                    if (!snapshotTable.equals(partitionTableAndColumn.first)
                            || !snapshotTable.containColumn(partitionColumn.getName())) {
                        return false;
                    }

                    Map<String, Range<PartitionKey>> snapshotPartitionMap = PartitionUtil.getPartitionKeyRange(
                            snapshotTable, partitionColumn, MaterializedView.getPartitionExpr(materializedView));
                    Map<String, Range<PartitionKey>> currentPartitionMap = PartitionUtil.getPartitionKeyRange(
                            table, partitionColumn, MaterializedView.getPartitionExpr(materializedView));
                    boolean changed =
                            SyncPartitionUtils.hasPartitionChange(snapshotPartitionMap, currentPartitionMap);
                    if (changed) {
                        return true;
                    }
                }
            }
        } catch (UserException e) {
            LOG.warn("Materialized view compute partition change failed", e);
            return true;
        }
        return false;
    }

    private boolean checkBaseTablePartitionChange(MaterializedView mv) {
        List<Database> dbs = collectDatabases(mv);
        // check snapshotBaseTables and current tables in catalog
        if (!StatementPlanner.tryLockDatabases(dbs, Config.mv_refresh_try_lock_timeout_ms, TimeUnit.MILLISECONDS)) {
            throw new LockTimeoutException("Failed to lock databases: " + Joiner.on(",").join(dbs.stream()
                    .map(Database::getFullName).collect(Collectors.toList())));
        }
        try {
            if (snapshotBaseTables.values().stream().anyMatch(t -> checkBaseTableSnapshotInfoChanged(t.first, t.second))) {
                return true;
            }
        } finally {
            StatementPlanner.unlockDatabases(dbs);
        }
        return false;
    }

    @VisibleForTesting
    public void refreshMaterializedView(MvTaskRunContext mvContext, ExecPlan execPlan, InsertStmt insertStmt)
            throws Exception {
        Preconditions.checkNotNull(execPlan);
        Preconditions.checkNotNull(insertStmt);
        ConnectContext ctx = mvContext.getCtx();

        if (mvContext.getTaskRun().isKilled()) {
            LOG.warn("[QueryId:{}] refresh materialized view {} is killed", ctx.getQueryId(),
                    materializedView.getName());
            throw new UserException("User Cancelled");
        }

        StmtExecutor executor = new StmtExecutor(ctx, insertStmt);
        ctx.setExecutor(executor);
        if (ctx.getParent() != null && ctx.getParent().getExecutor() != null) {
            StmtExecutor parentStmtExecutor = ctx.getParent().getExecutor();
            parentStmtExecutor.registerSubStmtExecutor(executor);
        }
        ctx.setStmtId(new AtomicInteger().incrementAndGet());
        ctx.setExecutionId(UUIDUtil.toTUniqueId(ctx.getQueryId()));
        ctx.getSessionVariable().setEnableInsertStrict(false);
        try {
            executor.handleDMLStmtWithProfile(execPlan, insertStmt);
        } catch (Exception e) {
            LOG.warn("refresh materialized view {} failed: {}", materializedView.getName(), e);
            throw e;
        } finally {
            auditAfterExec(mvContext, executor.getParsedStmt(), executor.getQueryStatisticsForAuditLog());
        }
    }

    @VisibleForTesting
    public Map<Long, Pair<BaseTableInfo, Table>> collectBaseTables(MaterializedView materializedView) {
        Map<Long, Pair<BaseTableInfo, Table>> tables = Maps.newHashMap();
        List<BaseTableInfo> baseTableInfos = materializedView.getBaseTableInfos();

        List<Database> dbs = collectDatabases(materializedView);
        if (!StatementPlanner.tryLockDatabases(dbs, Config.mv_refresh_try_lock_timeout_ms, TimeUnit.MILLISECONDS)) {
            throw new LockTimeoutException("Failed to lock databases: " + Joiner.on(",").join(dbs.stream()
                    .map(Database::getFullName).collect(Collectors.toList())));
        }
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            for (BaseTableInfo baseTableInfo : baseTableInfos) {
                Table table = baseTableInfo.getTable();
                if (table == null) {
                    LOG.warn("table {} do not exist when refreshing materialized view:{}",
                            baseTableInfo.getTableInfoStr(), materializedView.getName());
                    throw new DmlException("Materialized view base table: %s not exist.", baseTableInfo.getTableInfoStr());
                }

                if (table.isView()) {
                    // skip to collect snapshots for views
                } else if (table.isOlapTable()) {
                    OlapTable copied = DeepCopy.copyWithGson(table, OlapTable.class);
                    if (copied == null) {
                        throw new DmlException("Failed to copy olap table: %s", table.getName());
                    }
                    tables.put(table.getId(), Pair.create(baseTableInfo, copied));
                } else if (table.isOlapMaterializedView()) {
                    MaterializedView copied = DeepCopy.copyWithGson(table, MaterializedView.class);
                    if (copied == null) {
                        throw new DmlException("Failed to copy materialized view: %s", table.getName());
                    }
                    tables.put(table.getId(), Pair.create(baseTableInfo, copied));
                } else if (table.isCloudNativeTable()) {
                    LakeTable copied = DeepCopy.copyWithGson(table, LakeTable.class);
                    if (copied == null) {
                        throw new DmlException("Failed to copy lake table: %s", table.getName());
                    }
                    tables.put(table.getId(), Pair.create(baseTableInfo, copied));
                } else if (table.isCloudNativeMaterializedView()) {
                    LakeMaterializedView copied = DeepCopy.copyWithGson(table, LakeMaterializedView.class);
                    if (copied == null) {
                        throw new DmlException("Failed to copy lake materialized view: %s", table.getName());
                    }
                    tables.put(table.getId(), Pair.create(baseTableInfo, copied));
                } else {
                    // for other table types, use the table directly which needs to lock if visits the table metadata.
                    tables.put(table.getId(), Pair.create(baseTableInfo, table));
                }
            }
        } finally {
            StatementPlanner.unlockDatabases(dbs);
        }
        LOG.info("Collect base table snapshot infos for materialized view: {}, cost: {} ms",
                materializedView.getName(), stopwatch.elapsed(TimeUnit.MILLISECONDS));
        return tables;
    }

    private Map<String, String> getPartitionProperties(MaterializedView materializedView) {
        Map<String, String> partitionProperties = new HashMap<>(4);
        partitionProperties.put("replication_num",
                String.valueOf(materializedView.getDefaultReplicationNum()));
        partitionProperties.put("storage_medium", materializedView.getStorageMedium());
        String storageCooldownTime =
                materializedView.getTableProperty().getProperties().get("storage_cooldown_time");
        if (storageCooldownTime != null
                && !storageCooldownTime.equals(String.valueOf(DataProperty.MAX_COOLDOWN_TIME_MS))) {
            // cast long str to time str e.g.  '1587473111000' -> '2020-04-21 15:00:00'
            String storageCooldownTimeStr = TimeUtils.longToTimeString(Long.parseLong(storageCooldownTime));
            partitionProperties.put("storage_cooldown_time", storageCooldownTimeStr);
        }
        return partitionProperties;
    }

    private DistributionDesc getDistributionDesc(MaterializedView materializedView) {
        DistributionInfo distributionInfo = materializedView.getDefaultDistributionInfo();
        if (distributionInfo instanceof HashDistributionInfo) {
            List<String> distColumnNames = new ArrayList<>();
            for (Column distributionColumn : ((HashDistributionInfo) distributionInfo).getDistributionColumns()) {
                distColumnNames.add(distributionColumn.getName());
            }
            return new HashDistributionDesc(distributionInfo.getBucketNum(), distColumnNames);
        } else {
            return new RandomDistributionDesc();
        }
    }

    private void addPartitions(Database database, MaterializedView materializedView,
                               Map<String, Range<PartitionKey>> adds, Map<String, String> partitionProperties,
                               DistributionDesc distributionDesc) {
        if (adds.isEmpty()) {
            return;
        }
        List<PartitionDesc> partitionDescs = Lists.newArrayList();

        for (Map.Entry<String, Range<PartitionKey>> addEntry : adds.entrySet()) {
            String mvPartitionName = addEntry.getKey();
            Range<PartitionKey> partitionKeyRange = addEntry.getValue();

            String lowerBound = partitionKeyRange.lowerEndpoint().getKeys().get(0).getStringValue();
            String upperBound = partitionKeyRange.upperEndpoint().getKeys().get(0).getStringValue();
            boolean isMaxValue = partitionKeyRange.upperEndpoint().isMaxValue();
            PartitionValue upperPartitionValue;
            if (isMaxValue) {
                upperPartitionValue = PartitionValue.MAX_VALUE;
            } else {
                upperPartitionValue = new PartitionValue(upperBound);
            }
            PartitionKeyDesc partitionKeyDesc = new PartitionKeyDesc(
                    Collections.singletonList(new PartitionValue(lowerBound)),
                    Collections.singletonList(upperPartitionValue));
            SingleRangePartitionDesc singleRangePartitionDesc =
                    new SingleRangePartitionDesc(false, mvPartitionName, partitionKeyDesc, partitionProperties);
            partitionDescs.add(singleRangePartitionDesc);
        }

        // create partitions in small batch, to avoid create too many partitions at once
        for (List<PartitionDesc> batch : ListUtils.partition(partitionDescs, CREATE_PARTITION_BATCH_SIZE)) {
            RangePartitionDesc rangePartitionDesc =
                    new RangePartitionDesc(materializedView.getPartitionColumnNames(), batch);
            AddPartitionClause alterPartition = new AddPartitionClause(rangePartitionDesc, distributionDesc,
                    partitionProperties, false);
            try {
                GlobalStateMgr.getCurrentState().getLocalMetastore().addPartitions(
                        database, materializedView.getName(), alterPartition);
            } catch (Exception e) {
                throw new DmlException("Expression add partition failed: %s, db: %s, table: %s", e, e.getMessage(),
                        database.getFullName(), materializedView.getName());
            }
            Uninterruptibles.sleepUninterruptibly(Config.mv_create_partition_batch_interval_ms, TimeUnit.MILLISECONDS);
        }
    }

    private void dropPartition(Database database, MaterializedView materializedView, String mvPartitionName) {
        String dropPartitionName = materializedView.getPartition(mvPartitionName).getName();
        if (!database.writeLockAndCheckExist()) {
            throw new DmlException("drop partition failed. database:" + database.getFullName() + " not exist");
        }
        try {
            // check
            Table mv = database.getTable(materializedView.getId());
            if (mv == null) {
                throw new DmlException("drop partition failed. mv:" + materializedView.getName() + " not exist");
            }
            Partition mvPartition = mv.getPartition(dropPartitionName);
            if (mvPartition == null) {
                throw new DmlException("drop partition failed. partition:" + dropPartitionName + " not exist");
            }

            GlobalStateMgr.getCurrentState().dropPartition(
                    database, materializedView,
                    new DropPartitionClause(false, dropPartitionName, false, true));
        } catch (Exception e) {
            throw new DmlException("Expression add partition failed: %s, db: %s, table: %s", e, e.getMessage(),
                    database.getFullName(), materializedView.getName());
        } finally {
            database.writeUnlock();
        }
    }

    /**
     * For external table, the partition name is normalized which should convert it into original partition name.
     * <p>
     * For multi-partition columns, `refTableAndPartitionNames` is not fully exact to describe which partitions
     * of ref base table are refreshed, use `getSelectedPartitionInfosOfExternalTable` later if we can solve the multi
     * partition columns problem.
     * eg:
     * partitionName1 : par_col=0/par_date=2020-01-01 => p20200101
     * partitionName2 : par_col=1/par_date=2020-01-01 => p20200101
     */
    private Set<String> convertMVPartitionNameToRealPartitionName(Table table, String mvPartitionName) {
        if (!table.isNativeTableOrMaterializedView()) {
            Map<String, Set<String>> refBaseTableRangePartitionMap = mvContext.getExternalRefBaseTableMVPartitionMap();
            Preconditions.checkState(refBaseTableRangePartitionMap.containsKey(mvPartitionName));
            return refBaseTableRangePartitionMap.get(mvPartitionName);
        } else {
            return Sets.newHashSet(mvPartitionName);
        }
    }

    /**
     * @param mvToRefreshedPartitions :  to-refreshed materialized view partition names
     * @return : return to-refreshed base table's table name and partition names mapping
     */
    private Map<Table, Set<String>> getRefTableRefreshPartitions(Set<String> mvToRefreshedPartitions) {
        Table refBaseTable = mvContext.getRefBaseTable();
        Map<Table, Set<String>> refTableAndPartitionNames = Maps.newHashMap();
        for (Pair<BaseTableInfo, Table> tablePair : snapshotBaseTables.values()) {
            Table table = tablePair.second;
            if (refBaseTable != null && refBaseTable == table) {
                Set<String> needRefreshTablePartitionNames = Sets.newHashSet();
                Map<String, Set<String>> mvToBaseNameRef = mvContext.getMvRefBaseTableIntersectedPartitions();
                for (String mvPartitionName : mvToRefreshedPartitions) {
                    needRefreshTablePartitionNames.addAll(mvToBaseNameRef.get(mvPartitionName));
                }
                refTableAndPartitionNames.put(table, needRefreshTablePartitionNames);
                return refTableAndPartitionNames;
            }
        }
        return refTableAndPartitionNames;
    }

    /**
     * Return all non-ref base table and refreshed partitions.
     */
    private Map<Table, Set<String>> getNonRefTableRefreshPartitions() {
        Table partitionTable = mvContext.getRefBaseTable();
        Map<Table, Set<String>> tableNamePartitionNames = Maps.newHashMap();
        for (Pair<BaseTableInfo, Table> tablePair : snapshotBaseTables.values()) {
            Table table = tablePair.second;
            if (partitionTable != null && partitionTable.equals(table)) {
                // do nothing
            } else {
                if (table.isNativeTableOrMaterializedView()) {
                    tableNamePartitionNames.put(table, ((OlapTable) table).getVisiblePartitionNames());
                } else if (table.isView()) {
                    // do nothing
                } else {
                    tableNamePartitionNames.put(table, Sets.newHashSet(PartitionUtil.getPartitionNames(table)));
                }
            }
        }
        return tableNamePartitionNames;
    }

    /**
     * Collect base olap tables and its partition infos based on refreshed table infos.
     *
     * @param baseTableAndPartitionNames : refreshed base table and its partition names mapping.
     * @return
     */
    private Map<Long, Map<String, MaterializedView.BasePartitionInfo>> getSelectedPartitionInfosOfOlapTable(
            Map<Table, Set<String>> baseTableAndPartitionNames) {
        Map<Long, Map<String, MaterializedView.BasePartitionInfo>> changedOlapTablePartitionInfos = Maps.newHashMap();
        for (Map.Entry<Table, Set<String>> entry : baseTableAndPartitionNames.entrySet()) {
            if (entry.getKey().isNativeTableOrMaterializedView()) {
                Map<String, MaterializedView.BasePartitionInfo> partitionInfos = Maps.newHashMap();
                OlapTable olapTable = (OlapTable) entry.getKey();
                for (String partitionName : entry.getValue()) {
                    Partition partition = olapTable.getPartition(partitionName);
                    MaterializedView.BasePartitionInfo basePartitionInfo = new MaterializedView.BasePartitionInfo(
                            partition.getId(), partition.getVisibleVersion(), partition.getVisibleVersionTime());
                    partitionInfos.put(partition.getName(), basePartitionInfo);
                }
                LOG.info("Collect olap base table {}'s refreshed partition infos: {}", olapTable.getName(), partitionInfos);
                changedOlapTablePartitionInfos.put(olapTable.getId(), partitionInfos);
            }
        }
        return changedOlapTablePartitionInfos;
    }

    /**
     * Collect base hive tables and its partition infos based on refreshed table infos.
     *
     * @param baseTableAndPartitionNames : refreshed base table and its partition names mapping.
     * @return
     */
    private Map<BaseTableInfo, Map<String, MaterializedView.BasePartitionInfo>> getSelectedPartitionInfosOfExternalTable(
            Map<Table, Set<String>> baseTableAndPartitionNames) {
        Map<BaseTableInfo, Map<String, MaterializedView.BasePartitionInfo>> changedOlapTablePartitionInfos =
                Maps.newHashMap();
        for (Map.Entry<Table, Set<String>> entry : baseTableAndPartitionNames.entrySet()) {
            if (entry.getKey().isHiveTable() || entry.getKey().isJDBCTable() || entry.getKey().isIcebergTable()) {
                Table table = entry.getKey();
                Optional<BaseTableInfo> baseTableInfoOptional = materializedView.getBaseTableInfos().stream().filter(
                                baseTableInfo -> baseTableInfo.getTableIdentifier().equals(table.getTableIdentifier())).
                        findAny();
                if (!baseTableInfoOptional.isPresent()) {
                    continue;
                }
                BaseTableInfo baseTableInfo = baseTableInfoOptional.get();
                Map<String, MaterializedView.BasePartitionInfo> partitionInfos =
                        getSelectedPartitionInfos(table, Lists.newArrayList(entry.getValue()),
                                baseTableInfo);
                changedOlapTablePartitionInfos.put(baseTableInfo, partitionInfos);
            }
        }
        return changedOlapTablePartitionInfos;
    }

    /**
     * @param table                  : input table to collect refresh partition infos
     * @param selectedPartitionNames : input table refreshed partition names
     * @param baseTableInfo          : input table's base table info
     * @return : return the given table's refresh partition infos
     */
    private Map<String, MaterializedView.BasePartitionInfo> getSelectedPartitionInfos(Table table,
                                                                                      List<String> selectedPartitionNames,
                                                                                      BaseTableInfo baseTableInfo) {
        // sort selectedPartitionNames before the for loop, otherwise the order of partition names may be
        // different in selectedPartitionNames and partitions and will lead to infinite partition refresh.
        Collections.sort(selectedPartitionNames);
        Map<String, MaterializedView.BasePartitionInfo> partitionInfos = Maps.newHashMap();
        List<com.starrocks.connector.PartitionInfo> partitions = GlobalStateMgr.
                getCurrentState().getMetadataMgr().getPartitions(baseTableInfo.getCatalogName(), table,
                        selectedPartitionNames);
        for (int index = 0; index < selectedPartitionNames.size(); ++index) {
            long modifiedTime = partitions.get(index).getModifiedTime();
            partitionInfos.put(selectedPartitionNames.get(index),
                    new MaterializedView.BasePartitionInfo(-1, modifiedTime, modifiedTime));
        }
        return partitionInfos;
    }

    /**
     * Extract refreshed/scanned base table and its refreshed partition names
     * NOTE: this is used to trace in task_runs.
     */
    private Map<String, Set<String>> getBaseTableRefreshedPartitionsByExecPlan(
            ExecPlan execPlan) {
        Map<String, Set<String>> baseTableRefreshPartitionNames = Maps.newHashMap();
        List<ScanNode> scanNodes = execPlan.getScanNodes();
        for (ScanNode scanNode : scanNodes) {
            if (scanNode instanceof OlapScanNode) {
                OlapScanNode olapScanNode = (OlapScanNode) scanNode;
                OlapTable olapTable = olapScanNode.getOlapTable();
                if (olapScanNode.getSelectedPartitionNames() != null &&
                        !olapScanNode.getSelectedPartitionNames().isEmpty()) {
                    baseTableRefreshPartitionNames.put(olapTable.getName(),
                            new HashSet<>(olapScanNode.getSelectedPartitionNames()));
                } else {
                    List<Long> selectedPartitionIds = olapScanNode.getSelectedPartitionIds();
                    Set<String> selectedPartitionNames = selectedPartitionIds.stream()
                            .map(p -> olapTable.getPartition(p).getName())
                            .collect(Collectors.toSet());
                    baseTableRefreshPartitionNames.put(olapTable.getName(), selectedPartitionNames);
                }
            } else if (scanNode instanceof HdfsScanNode) {
                HdfsScanNode hdfsScanNode = (HdfsScanNode) scanNode;
                HiveTable hiveTable = (HiveTable) hdfsScanNode.getHiveTable();
                Optional<BaseTableInfo> baseTableInfoOptional = materializedView.getBaseTableInfos().stream().filter(
                                baseTableInfo -> baseTableInfo.getTableIdentifier().equals(hiveTable.getTableIdentifier())).
                        findAny();
                if (!baseTableInfoOptional.isPresent()) {
                    continue;
                }
                Set<String> selectedPartitionNames = Sets.newHashSet(
                        getSelectedPartitionNamesOfHiveTable(hiveTable, hdfsScanNode));
                baseTableRefreshPartitionNames.put(hiveTable.getName(), selectedPartitionNames);
            } else {
                // do nothing.
            }
        }
        return baseTableRefreshPartitionNames;
    }

    /**
     * Extract hive partition names from hdfs scan node.
     */
    private List<String> getSelectedPartitionNamesOfHiveTable(HiveTable hiveTable, HdfsScanNode hdfsScanNode) {
        List<String> partitionColumnNames = hiveTable.getPartitionColumnNames();
        List<String> selectedPartitionNames;
        if (hiveTable.isUnPartitioned()) {
            selectedPartitionNames = Lists.newArrayList(hiveTable.getTableName());
        } else {
            Collection<Long> selectedPartitionIds = hdfsScanNode.getScanNodePredicates().getSelectedPartitionIds();
            List<PartitionKey> selectedPartitionKey = Lists.newArrayList();
            for (Long selectedPartitionId : selectedPartitionIds) {
                selectedPartitionKey
                        .add(hdfsScanNode.getScanNodePredicates().getIdToPartitionKey().get(selectedPartitionId));
            }
            selectedPartitionNames = selectedPartitionKey.stream().map(partitionKey ->
                    PartitionUtil.toHivePartitionName(partitionColumnNames, partitionKey)).collect(Collectors.toList());
        }
        return selectedPartitionNames;
    }
}