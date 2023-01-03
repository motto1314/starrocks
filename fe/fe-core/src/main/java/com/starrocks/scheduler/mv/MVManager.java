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


package com.starrocks.scheduler.mv;

import com.google.common.base.Preconditions;
import com.google.gson.annotations.SerializedName;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.MvId;
import com.starrocks.catalog.PartitionInfo;
import com.starrocks.common.DdlException;
import com.starrocks.common.io.Text;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.LocalMetastore;
import com.starrocks.sql.ast.CreateMaterializedViewStatement;
import com.starrocks.sql.common.UnsupportedException;
import com.starrocks.thrift.TMVMaintenanceTasks;
import com.starrocks.thrift.TMVReportEpochTask;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manage lifetime of MV, including create, build, refresh, destroy
 * TODO(Murphy) refactor all MV management code into here
 */
public class MVManager {
    private static final Logger LOG = LogManager.getLogger(LocalMetastore.class);
    private static final MVManager INSTANCE = new MVManager();

    private final Map<MvId, MVMaintenanceJob> jobMap = new ConcurrentHashMap<>();

    private MVManager() {
    }

    public static MVManager getInstance() {
        return INSTANCE;
    }

    public MaterializedView createSinkTable(CreateMaterializedViewStatement stmt, PartitionInfo partitionInfo,
                                            long mvId, long dbId)
            throws DdlException {
        return IMTCreator.createSinkTable(stmt, partitionInfo, mvId, dbId);
    }

    /**
     * Reload jobs from meta store
     */
    public long reload(DataInputStream input, long checksum) throws IOException {
        Preconditions.checkState(jobMap.isEmpty());

        try {
            String str = Text.readString(input);
            SerializedJobs data = GsonUtils.GSON.fromJson(str, SerializedJobs.class);
            if (CollectionUtils.isNotEmpty(data.jobList)) {
                for (MVMaintenanceJob job : data.jobList) {
                    long viewId = job.getViewId();
                    MaterializedView view;
                    jobMap.put(job.getView().getMvId(), job);
                }
                LOG.info("reload MV maintenance jobs: {}", data.jobList);
                LOG.debug("reload MV maintenance job details: {}", str);
            }
            checksum ^= data.jobList.size();
        } catch (EOFException ignored) {
        }
        return checksum;
    }

    public void replay(MVMaintenanceJob job) throws IOException {
        Preconditions.checkState(jobMap.isEmpty());

        try {
            jobMap.put(job.getView().getMvId(), job);
            LOG.info("replay MV maintenance jobs: {}", job);
        } catch (Exception ignored) {
            LOG.warn("Replay MV maintenannce job failed: {}", job);
        }
    }

    /**
     * Store jobs in meta store
     */
    public long store(DataOutputStream output, long checksum) throws IOException {
        SerializedJobs data = new SerializedJobs();
        data.jobList = new ArrayList<>(jobMap.values());
        String json = GsonUtils.GSON.toJson(data);
        Text.writeString(output, json);
        checksum ^= data.jobList.size();
        return checksum;
    }

    /**
     * Prepare maintenance work for materialized view
     * 1. Create Intermediate Materialized Table(IMT)
     * 2. Store metadata of Job in metastore
     * 3. Create job for MV maintenance
     * 4. Compute physical topology of MV job
     */
    public void prepareMaintenanceWork(CreateMaterializedViewStatement stmt, MaterializedView view)
            throws DdlException {
        if (!view.getRefreshScheme().isIncremental()) {
            return;
        }

        try {
            if (jobMap.get(view.getMvId()) != null) {
                throw new DdlException("MV already existed");
            }

            // Create the job but not execute it
            MVMaintenanceJob job = new MVMaintenanceJob(view);
            Preconditions.checkState(jobMap.putIfAbsent(view.getMvId(), job) == null, "job already existed");

            IMTCreator.createIMT(stmt, view);

            // TODO(murphy) atomic persist the meta of MV (IMT, MaintenancePlan) along with materialized view
            GlobalStateMgr.getCurrentState().getEditLog().logMVJobState(job);
            LOG.info("create the maintenance job for MV: {}", view.getName());
        } catch (Exception e) {
            jobMap.remove(view.getMvId());
            LOG.warn("prepare MV {} failed, ", view.getName(), e);
        }
    }

    /**
     * Start the maintenance job for MV after created
     * 1. Schedule the maintenance task in executor
     * 2. Coordinate the epoch/transaction progress: reading changelog of base table and incremental refresh the MV
     * 3. Maintain the visibility of MV and job metadata
     */
    public void startMaintainMV(MaterializedView view) {
        if (!view.getRefreshScheme().isIncremental()) {
            return;
        }
        MVMaintenanceJob job = Preconditions.checkNotNull(getJob(view.getMvId()));
        job.startJob();
        LOG.info("Start maintenance job for mv {}", view.getName());
    }

    public void pauseMaintainMV(MaterializedView view) {
        if (!view.getRefreshScheme().isIncremental()) {
            return;
        }
        MVMaintenanceJob job = Preconditions.checkNotNull(getJob(view.getMvId()));
        job.pauseJob();
    }

    /**
     * Stop the maintenance job for MV after dropped
     */
    public void stopMaintainMV(MaterializedView view) {
        if (!view.getRefreshScheme().isIncremental()) {
            return;
        }
        MVMaintenanceJob job = getJob(view.getMvId());
        if (job == null) {
            LOG.warn("MV job not exists {}", view.getName());
            return;
        }
        job.stopJob();
        jobMap.remove(view.getMvId());
        LOG.info("Remove maintenance job for mv: {}", view.getName());
    }

    /**
     * Refresh the MV
     */
    public void onTxnPublish(MaterializedView view) {
        LOG.info("onTxnPublish: {}", view);
        MVMaintenanceJob job = Preconditions.checkNotNull(getJob(view.getMvId()));
        job.onTransactionPublish();
    }

    public void onReportEpoch(TMVMaintenanceTasks request) {
        Preconditions.checkArgument(request.isSetDb_id(), "required");
        Preconditions.checkArgument(request.isSetMv_id(), "required");
        Preconditions.checkArgument(request.isSetTask_id(), "required");
        Preconditions.checkArgument(request.isSetReport_epoch(), "must be report");

        long dbId = request.getDb_id();
        long tableId = request.getMv_id();
        long taskId = request.getTask_id();

        MVMaintenanceJob job = Preconditions.checkNotNull(getJob(new MvId(dbId, tableId)));
        MVMaintenanceTask task = job.getTask(taskId);
        TMVReportEpochTask report = request.getReport_epoch();
        task.updateEpochState(report);

        // TODO(murphy) trigger the epoch commit in report event
    }

    /**
     * Re-Schedule the MV maintenance job
     * 1. Tablet migration
     * 2. Cluster rescale
     * 3. Temporary failure happens(FE or BE)
     */
    public void reScheduleMaintainMV(MvId mvId) {
        throw UnsupportedException.unsupportedException("re-schedule mv job is not supported");
    }

    /**
     * Rebuild the maintenance job for critical changes
     * 1. Schema change of MV (should be optimized later)
     * 2. Binlog lost
     * 3. Base table schema change (should be optimized later)
     */
    public void rebuildMaintainMV(MaterializedView view) {
        if (!view.getRefreshScheme().isIncremental()) {
            return;
        }
        throw UnsupportedException.unsupportedException("rebuild mv job is not supported");
    }

    private MVMaintenanceJob getJob(MvId mvId) {
        return jobMap.get(mvId);
    }

    public List<MVMaintenanceJob> getRunnableJobs() {
        return this.jobMap.values().stream().filter(MVMaintenanceJob::isRunnable).collect(Collectors.toList());
    }

    static class SerializedJobs {
        @SerializedName("jobList")
        List<MVMaintenanceJob> jobList;
    }

}
