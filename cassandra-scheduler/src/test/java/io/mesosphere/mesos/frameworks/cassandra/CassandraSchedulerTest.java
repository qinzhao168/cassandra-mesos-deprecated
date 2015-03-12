/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mesosphere.mesos.frameworks.cassandra;

import com.google.protobuf.InvalidProtocolBufferException;
import io.mesosphere.mesos.util.ProtoUtils;
import io.mesosphere.mesos.util.Tuple2;
import org.apache.mesos.Protos;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.Assert.*;

public class CassandraSchedulerTest extends AbstractSchedulerTest {
    CassandraScheduler scheduler;
    MockSchedulerDriver driver;

    @Test
    public void testLaunchNewCluster() throws InvalidProtocolBufferException {
        cleanState();

        driver.callRegistered(frameworkId);

        // rollout slave #1

        Protos.TaskInfo executorMetadata1 = launchExecutor(slaves[0], 1);

        // next offer must return nothing for the same slave !

        noopOnOffer(slaves[0], 1);

        //
        // at this point the executor for slave #1 is known but the server must not be started because we can't fulfil
        // the seed-node-count requirement
        //

        executorTaskRunning(executorMetadata1);
        noopOnOffer(slaves[0], 1);
        assertEquals(Collections.singletonList("127.1.1.1"), cluster.getSeedNodes());
        assertThat(clusterState.nodeCounts()).isEqualTo(new NodeCounts(1, 1));

        // rollout slave #2

        Protos.TaskInfo executorMetadata2 = launchExecutor(slaves[1], 2);

        // rollout slave #3

        Protos.TaskInfo executorMetadata3 = launchExecutor(slaves[2], 3);

        // next offer must return nothing for the same slave !

        noopOnOffer(slaves[1], 3);

        // next offer must return nothing for the same slave !

        noopOnOffer(slaves[2], 3);

        //
        // at this point all three slave have got metadata tasks
        //

        noopOnOffer(slaves[1], 3);
        noopOnOffer(slaves[2], 3);

        assertThat(clusterState.nodeCounts()).isEqualTo(new NodeCounts(3, 2));

        executorTaskRunning(executorMetadata2);

        assertThat(clusterState.nodeCounts()).isEqualTo(new NodeCounts(3, 2));

        //
        // now there are enough executor metadata to start the seed nodes - but not the non-seed nodes
        //

        // node must not start (it's not a seed node and no seed node is running)
        noopOnOffer(slaves[2], 3);

        launchTask(slaves[0], CassandraFrameworkProtos.TaskDetails.TaskType.CASSANDRA_SERVER_RUN);
        launchTask(slaves[1], CassandraFrameworkProtos.TaskDetails.TaskType.CASSANDRA_SERVER_RUN);
        // still - not able to start node #3
        noopOnOffer(slaves[2], 3);

        //
        // executor for node #3 started
        //

        executorTaskRunning(executorMetadata3);
        // still - not able to start node #3
        noopOnOffer(slaves[2], 3);

        //
        // simulate some health check states
        //

        sendHealthCheckResult(executorMetadata1, healthCheckDetailsFailed());
        assertThat(lastHealthCheckDetails(executorMetadata1))
            .isNot(healthy());
        sendHealthCheckResult(executorMetadata2, healthCheckDetailsFailed());
        assertThat(lastHealthCheckDetails(executorMetadata2))
            .isNot(healthy());
        // still - not able to start node #3
        noopOnOffer(slaves[2], 3);

        sendHealthCheckResult(executorMetadata1, healthCheckDetailsSuccess("JOINING", false));
        assertThat(lastHealthCheckDetails(executorMetadata1))
            .is(healthy())
            .has(operationMode("JOINING"));
        sendHealthCheckResult(executorMetadata2, healthCheckDetailsFailed());
        // still - not able to start node #3
        noopOnOffer(slaves[2], 3);

        //
        // one seed has started up
        //

        sendHealthCheckResult(executorMetadata1, healthCheckDetailsSuccess("NORMAL", true));
        assertThat(lastHealthCheckDetails(executorMetadata1))
            .is(healthy())
            .has(operationMode("NORMAL"));
        sendHealthCheckResult(executorMetadata2, healthCheckDetailsFailed());
        assertThat(lastHealthCheckDetails(executorMetadata2))
            .isNot(healthy());
        // node#3 can start now
        launchTask(slaves[2], CassandraFrameworkProtos.TaskDetails.TaskType.CASSANDRA_SERVER_RUN);

    }

    @Test
    public void testServerTaskRemove() throws InvalidProtocolBufferException {

        Protos.TaskInfo[] executorMetadata = threeNodeCluster();

        // cluster now up with 3 running nodes

        // server-task no longer running
        executorTaskError(executorMetadata[0]);

        // server-task cannot start again
        launchExecutor(slaves[0], 3);
        executorTaskRunning(executorMetadata[0]);
        launchTask(slaves[0], CassandraFrameworkProtos.TaskDetails.TaskType.CASSANDRA_SERVER_RUN);

    }

    @Test
    public void testRepair() throws InvalidProtocolBufferException {

        threeNodeCluster();

        clusterJob(CassandraFrameworkProtos.ClusterJobType.REPAIR);

        clusterJob(CassandraFrameworkProtos.ClusterJobType.REPAIR);

    }

    @Test
    public void testRepairCleanupRepairCleanup() throws InvalidProtocolBufferException {

        threeNodeCluster();

        clusterJob(CassandraFrameworkProtos.ClusterJobType.REPAIR);

        clusterJob(CassandraFrameworkProtos.ClusterJobType.CLEANUP);

        clusterJob(CassandraFrameworkProtos.ClusterJobType.REPAIR);

        clusterJob(CassandraFrameworkProtos.ClusterJobType.CLEANUP);

    }

    @Test
    public void testRepairWithFailingNode() throws InvalidProtocolBufferException {

        threeNodeCluster();

        partiallyFailingClusterJob(CassandraFrameworkProtos.ClusterJobType.REPAIR);

        partiallyFailingClusterJob(CassandraFrameworkProtos.ClusterJobType.REPAIR);

    }

    private void partiallyFailingClusterJob(CassandraFrameworkProtos.ClusterJobType clusterJobType) throws InvalidProtocolBufferException {
        CassandraFrameworkProtos.ClusterJobStatus currentClusterJob = cluster.getCurrentClusterJob();
        assertNull(currentClusterJob);

        // simulate API call
        cluster.startClusterTask(clusterJobType);

        currentClusterJob = cluster.getCurrentClusterJob();

        assertNotNull(currentClusterJob);

        assertFalse(currentClusterJob.hasCurrentNode());
        assertEquals(3, currentClusterJob.getRemainingNodesCount());
        assertEquals(0, currentClusterJob.getCompletedNodesCount());
        assertEquals(clusterJobType, currentClusterJob.getJobType());
        assertFalse(currentClusterJob.getAborted());
        assertTrue(currentClusterJob.hasStartedTimestamp());
        assertFalse(currentClusterJob.hasFinishedTimestamp());

        // launch job on a node
        Protos.TaskInfo taskInfo = launchTaskOnAny(CassandraFrameworkProtos.TaskDetails.TaskType.NODE_JOB);
        Protos.TaskInfo taskInfo1 = taskInfo;
        assertNotNull(taskInfo);
        assertEquals(executorIdValue(taskInfo) + '.' + clusterJobType.name(), taskIdValue(taskInfo));

        // no other slave must produce a task
        Tuple2<Protos.SlaveID, String> currentSlave = null;
        for (Tuple2<Protos.SlaveID, String> slave : slaves) {
            if (!slave._1.equals(taskInfo.getSlaveId()))
                noopOnOffer(slave, 3);
            else
                currentSlave = slave;
        }
        assertNotNull(currentSlave);

        // check cluster job
        currentClusterJob = cluster.getCurrentClusterJob();
        assertNotNull(currentClusterJob);

        assertTrue(currentClusterJob.hasCurrentNode());
        assertEquals(executorIdValue(taskInfo), currentClusterJob.getCurrentNode().getExecutorId());
        assertEquals(clusterJobType, currentClusterJob.getCurrentNode().getJobType());
        assertTrue(currentClusterJob.getCurrentNode().hasStartedTimestamp());
        assertFalse(currentClusterJob.getCurrentNode().hasFinishedTimestamp());
        assertEquals(2, currentClusterJob.getRemainingNodesCount());
        assertEquals(0, currentClusterJob.getCompletedNodesCount());
        assertEquals(clusterJobType, currentClusterJob.getJobType());
        assertFalse(currentClusterJob.getAborted());
        assertTrue(currentClusterJob.hasStartedTimestamp());
        assertFalse(currentClusterJob.hasFinishedTimestamp());

        // check job status submit

        CassandraFrameworkProtos.TaskDetails taskDetails = submitTask(currentSlave, CassandraFrameworkProtos.TaskDetails.TaskType.NODE_JOB_STATUS);
        assertNotNull(taskDetails);

        // simulate job status response

        CassandraFrameworkProtos.NodeJobStatus nodeJobStatus = initialNodeJobStatus(taskInfo, clusterJobType);

        scheduler.frameworkMessage(driver,
                Protos.ExecutorID.newBuilder().setValue(currentClusterJob.getCurrentNode().getExecutorId()).build(),
                currentSlave._1,
                CassandraFrameworkProtos.SlaveStatusDetails.newBuilder()
                        .setStatusDetailsType(CassandraFrameworkProtos.SlaveStatusDetails.StatusDetailsType.NODE_JOB_STATUS)
                        .setNodeJobStatus(nodeJobStatus)
                        .build().toByteArray());

        // check cluster job after 1st response

        currentClusterJob = cluster.getCurrentClusterJob();
        assertNotNull(currentClusterJob);

        assertTrue(currentClusterJob.hasCurrentNode());
        assertEquals(executorIdValue(taskInfo), currentClusterJob.getCurrentNode().getExecutorId());
        assertEquals(clusterJobType, currentClusterJob.getCurrentNode().getJobType());
        assertTrue(currentClusterJob.getCurrentNode().hasStartedTimestamp());
        assertFalse(currentClusterJob.getCurrentNode().hasFinishedTimestamp());
        assertEquals(2, currentClusterJob.getRemainingNodesCount());
        assertEquals(0, currentClusterJob.getCompletedNodesCount());
        assertEquals(clusterJobType, currentClusterJob.getJobType());
        assertFalse(currentClusterJob.getAborted());
        assertTrue(currentClusterJob.hasStartedTimestamp());
        assertFalse(currentClusterJob.hasFinishedTimestamp());
        //
        // we cannot compare this one:  assertEquals(nodeJobStatus.getStartedTimestamp(), currentClusterJob.getCurrentNode().getStartedTimestamp());
        assertEquals(Arrays.asList("foo", "bar", "baz"), currentClusterJob.getCurrentNode().getRemainingKeyspacesList());
        assertEquals(0, currentClusterJob.getCurrentNode().getProcessedKeyspacesCount());
        assertTrue(currentClusterJob.getCurrentNode().getRunning());
        assertEquals(taskIdValue(taskInfo), currentClusterJob.getCurrentNode().getTaskId());

        // node has finished ...

        taskDetails = submitTask(currentSlave, CassandraFrameworkProtos.TaskDetails.TaskType.NODE_JOB_STATUS);
        assertNotNull(taskDetails);

        finishJob(currentClusterJob, taskInfo, currentSlave, nodeJobStatus, clusterJobType);
        currentClusterJob = cluster.getCurrentClusterJob();

        // cluster job should have no current node yet

        assertNotNull(currentClusterJob);
        assertEquals(2, currentClusterJob.getRemainingNodesCount());
        assertEquals(1, currentClusterJob.getCompletedNodesCount());
        for (CassandraFrameworkProtos.NodeJobStatus jobStatus : currentClusterJob.getCompletedNodesList()) {
            if (jobStatus.getExecutorId().equals(executorIdValue(taskInfo))) {
                assertFalse(jobStatus.hasFailed());
                assertFalse(jobStatus.hasFailureMessage());
            }
        }
        assertFalse(currentClusterJob.hasCurrentNode());

        // 2nd node

        taskInfo = launchTaskOnAny(CassandraFrameworkProtos.TaskDetails.TaskType.NODE_JOB);
        Protos.TaskInfo taskInfo2 = taskInfo;
        Assert.assertNotEquals(executorId(taskInfo), executorId(taskInfo1));
        Assert.assertNotEquals(taskInfo.getSlaveId(), taskInfo1.getSlaveId());
        assertEquals(executorIdValue(taskInfo) + '.' + clusterJobType.name(), taskIdValue(taskInfo));

        assertNotNull(taskInfo);
        currentSlave = null;
        for (Tuple2<Protos.SlaveID, String> slave : slaves) {
            if (!slave._1.equals(taskInfo.getSlaveId()))
                noopOnOffer(slave, 3);
            else
                currentSlave = slave;
        }
        assertNotNull(currentSlave);
        initialNodeJobStatus(taskInfo, clusterJobType);

        // ... just finish 2nd node

        executorTaskError(taskInfo);
        currentClusterJob = cluster.getCurrentClusterJob();

        assertNotNull(currentClusterJob);
        assertEquals(1, currentClusterJob.getRemainingNodesCount());
        assertEquals(2, currentClusterJob.getCompletedNodesCount());
        for (CassandraFrameworkProtos.NodeJobStatus jobStatus : currentClusterJob.getCompletedNodesList()) {
            if (jobStatus.getExecutorId().equals(executorIdValue(taskInfo))) {
                assertTrue(jobStatus.getFailed());
                assertFalse(jobStatus.getFailureMessage().isEmpty());
            }
        }
        assertFalse(currentClusterJob.hasCurrentNode());

        // 3rd node

        taskInfo = launchTaskOnAny(CassandraFrameworkProtos.TaskDetails.TaskType.NODE_JOB);
        Assert.assertNotEquals(executorId(taskInfo), executorId(taskInfo1));
        Assert.assertNotEquals(taskInfo.getSlaveId(), taskInfo1.getSlaveId());
        Assert.assertNotEquals(executorId(taskInfo), executorId(taskInfo2));
        Assert.assertNotEquals(taskInfo.getSlaveId(), taskInfo2.getSlaveId());
        assertEquals(executorIdValue(taskInfo) + '.' + clusterJobType.name(), taskIdValue(taskInfo));

        assertNotNull(taskInfo);
        currentSlave = null;
        for (Tuple2<Protos.SlaveID, String> slave : slaves) {
            if (!slave._1.equals(taskInfo.getSlaveId()))
                noopOnOffer(slave, 3);
            else
                currentSlave = slave;
        }
        assertNotNull(currentSlave);
        nodeJobStatus = initialNodeJobStatus(taskInfo, clusterJobType);

        // ... just finish 3rd node

        finishJob(currentClusterJob, taskInfo, currentSlave, nodeJobStatus, clusterJobType);
        currentClusterJob = cluster.getCurrentClusterJob();

        // job finished

        assertNull(currentClusterJob);

        currentClusterJob = cluster.getLastClusterJob(clusterJobType);
        assertNotNull(currentClusterJob);

        assertFalse(currentClusterJob.hasCurrentNode());
        assertEquals(0, currentClusterJob.getRemainingNodesCount());
        assertEquals(3, currentClusterJob.getCompletedNodesCount());
        assertEquals(clusterJobType, currentClusterJob.getJobType());
        assertFalse(currentClusterJob.getAborted());
        assertTrue(currentClusterJob.hasStartedTimestamp());
        assertTrue(currentClusterJob.hasFinishedTimestamp());

        // no tasks

        for (Tuple2<Protos.SlaveID, String> slave : slaves) {
            noopOnOffer(slave, slaves.length);
        }
    }

    private void clusterJob(CassandraFrameworkProtos.ClusterJobType clusterJobType) throws InvalidProtocolBufferException {
        CassandraFrameworkProtos.ClusterJobStatus currentClusterJob = cluster.getCurrentClusterJob();
        assertNull(currentClusterJob);

        // simulate API call
        cluster.startClusterTask(clusterJobType);

        currentClusterJob = cluster.getCurrentClusterJob();
        assertNotNull(currentClusterJob);

        assertFalse(currentClusterJob.hasCurrentNode());
        assertEquals(3, currentClusterJob.getRemainingNodesCount());
        assertEquals(0, currentClusterJob.getCompletedNodesCount());
        assertEquals(clusterJobType, currentClusterJob.getJobType());
        assertFalse(currentClusterJob.getAborted());
        assertTrue(currentClusterJob.hasStartedTimestamp());
        assertFalse(currentClusterJob.hasFinishedTimestamp());

        // launch job on a node
        Protos.TaskInfo taskInfo = launchTaskOnAny(CassandraFrameworkProtos.TaskDetails.TaskType.NODE_JOB);
        Protos.TaskInfo taskInfo1 = taskInfo;
        assertNotNull(taskInfo);
        assertEquals(executorIdValue(taskInfo) + '.' + clusterJobType.name(), taskIdValue(taskInfo));

        // no other slave must produce a task
        Tuple2<Protos.SlaveID, String> currentSlave = null;
        for (Tuple2<Protos.SlaveID, String> slave : slaves) {
            if (!slave._1.equals(taskInfo.getSlaveId()))
                noopOnOffer(slave, 3);
            else
                currentSlave = slave;
        }
        assertNotNull(currentSlave);

        // check cluster job
        currentClusterJob = cluster.getCurrentClusterJob();
        assertNotNull(currentClusterJob);

        assertTrue(currentClusterJob.hasCurrentNode());
        assertEquals(executorIdValue(taskInfo), currentClusterJob.getCurrentNode().getExecutorId());
        assertEquals(clusterJobType, currentClusterJob.getCurrentNode().getJobType());
        assertTrue(currentClusterJob.getCurrentNode().hasStartedTimestamp());
        assertFalse(currentClusterJob.getCurrentNode().hasFinishedTimestamp());
        assertEquals(2, currentClusterJob.getRemainingNodesCount());
        assertEquals(0, currentClusterJob.getCompletedNodesCount());
        assertEquals(clusterJobType, currentClusterJob.getJobType());
        assertFalse(currentClusterJob.getAborted());
        assertTrue(currentClusterJob.hasStartedTimestamp());
        assertFalse(currentClusterJob.hasFinishedTimestamp());

        // check job status submit

        CassandraFrameworkProtos.TaskDetails taskDetails = submitTask(currentSlave, CassandraFrameworkProtos.TaskDetails.TaskType.NODE_JOB_STATUS);
        assertNotNull(taskDetails);

        // simulate job status response

        CassandraFrameworkProtos.NodeJobStatus nodeJobStatus = initialNodeJobStatus(taskInfo, clusterJobType);

        scheduler.frameworkMessage(driver,
                Protos.ExecutorID.newBuilder().setValue(currentClusterJob.getCurrentNode().getExecutorId()).build(),
                currentSlave._1,
                CassandraFrameworkProtos.SlaveStatusDetails.newBuilder()
                        .setStatusDetailsType(CassandraFrameworkProtos.SlaveStatusDetails.StatusDetailsType.NODE_JOB_STATUS)
                        .setNodeJobStatus(nodeJobStatus)
                        .build().toByteArray());

        // check cluster job after 1st response

        currentClusterJob = cluster.getCurrentClusterJob();
        assertNotNull(currentClusterJob);

        assertTrue(currentClusterJob.hasCurrentNode());
        assertEquals(executorIdValue(taskInfo), currentClusterJob.getCurrentNode().getExecutorId());
        assertEquals(clusterJobType, currentClusterJob.getCurrentNode().getJobType());
        assertTrue(currentClusterJob.getCurrentNode().hasStartedTimestamp());
        assertFalse(currentClusterJob.getCurrentNode().hasFinishedTimestamp());
        assertEquals(2, currentClusterJob.getRemainingNodesCount());
        assertEquals(0, currentClusterJob.getCompletedNodesCount());
        assertEquals(clusterJobType, currentClusterJob.getJobType());
        assertFalse(currentClusterJob.getAborted());
        assertTrue(currentClusterJob.hasStartedTimestamp());
        assertFalse(currentClusterJob.hasFinishedTimestamp());
        //
        // we cannot compare this one:  assertEquals(nodeJobStatus.getStartedTimestamp(), currentClusterJob.getCurrentNode().getStartedTimestamp());
        assertEquals(Arrays.asList("foo", "bar", "baz"), currentClusterJob.getCurrentNode().getRemainingKeyspacesList());
        assertEquals(0, currentClusterJob.getCurrentNode().getProcessedKeyspacesCount());
        assertTrue(currentClusterJob.getCurrentNode().getRunning());
        assertEquals(taskIdValue(taskInfo), currentClusterJob.getCurrentNode().getTaskId());

        // node has finished ...

        taskDetails = submitTask(currentSlave, CassandraFrameworkProtos.TaskDetails.TaskType.NODE_JOB_STATUS);
        assertNotNull(taskDetails);

        finishJob(currentClusterJob, taskInfo, currentSlave, nodeJobStatus, clusterJobType);
        currentClusterJob = cluster.getCurrentClusterJob();

        // cluster job should have no current node yet

        assertNotNull(currentClusterJob);
        assertEquals(2, currentClusterJob.getRemainingNodesCount());
        assertEquals(1, currentClusterJob.getCompletedNodesCount());
        assertFalse(currentClusterJob.hasCurrentNode());

        // 2nd node

        taskInfo = launchTaskOnAny(CassandraFrameworkProtos.TaskDetails.TaskType.NODE_JOB);
        Protos.TaskInfo taskInfo2 = taskInfo;
        Assert.assertNotEquals(executorId(taskInfo), executorId(taskInfo1));
        Assert.assertNotEquals(taskInfo.getSlaveId(), taskInfo1.getSlaveId());
        assertEquals(executorIdValue(taskInfo) + '.' + clusterJobType.name(), taskIdValue(taskInfo));

        assertNotNull(taskInfo);
        currentSlave = null;
        for (Tuple2<Protos.SlaveID, String> slave : slaves) {
            if (!slave._1.equals(taskInfo.getSlaveId()))
                noopOnOffer(slave, 3);
            else
                currentSlave = slave;
        }
        assertNotNull(currentSlave);
        nodeJobStatus = initialNodeJobStatus(taskInfo, clusterJobType);

        // ... just finish 2nd node

        finishJob(currentClusterJob, taskInfo, currentSlave, nodeJobStatus, clusterJobType);
        currentClusterJob = cluster.getCurrentClusterJob();

        assertNotNull(currentClusterJob);
        assertEquals(1, currentClusterJob.getRemainingNodesCount());
        assertEquals(2, currentClusterJob.getCompletedNodesCount());
        assertFalse(currentClusterJob.hasCurrentNode());

        // 3rd node

        taskInfo = launchTaskOnAny(CassandraFrameworkProtos.TaskDetails.TaskType.NODE_JOB);
        Assert.assertNotEquals(executorId(taskInfo), executorId(taskInfo1));
        Assert.assertNotEquals(taskInfo.getSlaveId(), taskInfo1.getSlaveId());
        Assert.assertNotEquals(executorId(taskInfo), executorId(taskInfo2));
        Assert.assertNotEquals(taskInfo.getSlaveId(), taskInfo2.getSlaveId());
        assertEquals(executorIdValue(taskInfo) + '.' + clusterJobType.name(), taskIdValue(taskInfo));

        assertNotNull(taskInfo);
        currentSlave = null;
        for (Tuple2<Protos.SlaveID, String> slave : slaves) {
            if (!slave._1.equals(taskInfo.getSlaveId()))
                noopOnOffer(slave, 3);
            else
                currentSlave = slave;
        }
        assertNotNull(currentSlave);
        nodeJobStatus = initialNodeJobStatus(taskInfo, clusterJobType);

        // ... just finish 3rd node

        finishJob(currentClusterJob, taskInfo, currentSlave, nodeJobStatus, clusterJobType);
        currentClusterJob = cluster.getCurrentClusterJob();

        // job finished

        assertNull(currentClusterJob);

        currentClusterJob = cluster.getLastClusterJob(clusterJobType);
        assertNotNull(currentClusterJob);

        assertFalse(currentClusterJob.hasCurrentNode());
        assertEquals(0, currentClusterJob.getRemainingNodesCount());
        assertEquals(3, currentClusterJob.getCompletedNodesCount());
        assertEquals(clusterJobType, currentClusterJob.getJobType());
        assertFalse(currentClusterJob.getAborted());
        assertTrue(currentClusterJob.hasStartedTimestamp());
        assertTrue(currentClusterJob.hasFinishedTimestamp());
        for (CassandraFrameworkProtos.NodeJobStatus jobStatus : currentClusterJob.getCompletedNodesList()) {
            assertEquals(clusterJobType, jobStatus.getJobType());
            assertEquals(3, jobStatus.getProcessedKeyspacesCount());
            assertEquals(0, jobStatus.getRemainingKeyspacesCount());
            assertFalse(jobStatus.getRunning());
        }

        // no tasks

        for (Tuple2<Protos.SlaveID, String> slave : slaves) {
            noopOnOffer(slave, slaves.length);
        }
    }

    private static CassandraFrameworkProtos.NodeJobStatus initialNodeJobStatus(Protos.TaskInfo taskInfo, CassandraFrameworkProtos.ClusterJobType clusterJobType) {
        return CassandraFrameworkProtos.NodeJobStatus.newBuilder()
                .setJobType(clusterJobType)
                .setExecutorId(executorIdValue(taskInfo))
                .setTaskId(taskIdValue(taskInfo))
                .setRunning(true)
                .setStartedTimestamp(System.currentTimeMillis())
                .addAllRemainingKeyspaces(Arrays.asList("foo", "bar", "baz"))
                .build();
    }

    private void finishJob(CassandraFrameworkProtos.ClusterJobStatus currentClusterJob, Protos.TaskInfo taskInfo, Tuple2<Protos.SlaveID, String> currentSlave, CassandraFrameworkProtos.NodeJobStatus nodeJobStatus, CassandraFrameworkProtos.ClusterJobType clusterJobType) {
        nodeJobStatus = CassandraFrameworkProtos.NodeJobStatus.newBuilder()
                .setJobType(clusterJobType)
                .setExecutorId(executorIdValue(taskInfo))
                .setTaskId(taskIdValue(taskInfo))
                .setRunning(false)
                .setStartedTimestamp(nodeJobStatus.getStartedTimestamp())
                .setFinishedTimestamp(System.currentTimeMillis())
                .addAllProcessedKeyspaces(Arrays.asList(
                        CassandraFrameworkProtos.ClusterJobKeyspaceStatus.newBuilder()
                                .setDuration(1)
                                .setKeyspace("foo")
                                .setStatus("FOO")
                                .build(),
                        CassandraFrameworkProtos.ClusterJobKeyspaceStatus.newBuilder()
                                .setDuration(1)
                                .setKeyspace("bar")
                                .setStatus("BAR")
                                .build(),
                        CassandraFrameworkProtos.ClusterJobKeyspaceStatus.newBuilder()
                                .setDuration(1)
                                .setKeyspace("baz")
                                .setStatus("BAZ")
                                .build()
                ))
                .build();

        executorTaskFinished(taskInfo, CassandraFrameworkProtos.SlaveStatusDetails.newBuilder()
                .setStatusDetailsType(CassandraFrameworkProtos.SlaveStatusDetails.StatusDetailsType.NODE_JOB_STATUS)
                .setNodeJobStatus(nodeJobStatus)
                .build());
        scheduler.frameworkMessage(driver,
                Protos.ExecutorID.newBuilder().setValue(currentClusterJob.getCurrentNode().getExecutorId()).build(),
                currentSlave._1,
                CassandraFrameworkProtos.SlaveStatusDetails.newBuilder()
                        .setStatusDetailsType(CassandraFrameworkProtos.SlaveStatusDetails.StatusDetailsType.NODE_JOB_STATUS)
                        .setNodeJobStatus(nodeJobStatus)
                        .build().toByteArray());
    }

    private Protos.TaskInfo[] threeNodeCluster() throws InvalidProtocolBufferException {
        cleanState();

        // rollout slaves
        Protos.TaskInfo[] executorMetadata = new Protos.TaskInfo[]
                {
                        launchExecutor(slaves[0], 1),
                        launchExecutor(slaves[1], 2),
                        launchExecutor(slaves[2], 3)
                };

        executorTaskRunning(executorMetadata[0]);
        executorTaskRunning(executorMetadata[1]);
        executorTaskRunning(executorMetadata[2]);

        // launch servers

        launchTask(slaves[0], CassandraFrameworkProtos.TaskDetails.TaskType.CASSANDRA_SERVER_RUN);
        launchTask(slaves[1], CassandraFrameworkProtos.TaskDetails.TaskType.CASSANDRA_SERVER_RUN);

        sendHealthCheckResult(executorMetadata[0], healthCheckDetailsSuccess("NORMAL", true));
        sendHealthCheckResult(executorMetadata[1], healthCheckDetailsSuccess("NORMAL", true));

        launchTask(slaves[2], CassandraFrameworkProtos.TaskDetails.TaskType.CASSANDRA_SERVER_RUN);

        sendHealthCheckResult(executorMetadata[2], healthCheckDetailsSuccess("NORMAL", true));
        return executorMetadata;
    }

    private void executorTaskError(Protos.TaskInfo taskInfo) {
        scheduler.statusUpdate(driver, Protos.TaskStatus.newBuilder()
                .setExecutorId(executorId(taskInfo))
                .setHealthy(true)
                .setSlaveId(taskInfo.getSlaveId())
                .setSource(Protos.TaskStatus.Source.SOURCE_EXECUTOR)
                .setTaskId(taskInfo.getTaskId())
                .setTimestamp(System.currentTimeMillis())
                .setState(Protos.TaskState.TASK_ERROR)
                .build());
    }

    private void executorTaskRunning(Protos.TaskInfo taskInfo) {
        scheduler.statusUpdate(driver, Protos.TaskStatus.newBuilder()
                .setExecutorId(executorId(taskInfo))
                .setHealthy(true)
                .setSlaveId(taskInfo.getSlaveId())
                .setSource(Protos.TaskStatus.Source.SOURCE_EXECUTOR)
                .setTaskId(taskInfo.getTaskId())
                .setTimestamp(System.currentTimeMillis())
                .setState(Protos.TaskState.TASK_RUNNING)
                .setData(CassandraFrameworkProtos.SlaveStatusDetails.newBuilder()
                    .setStatusDetailsType(CassandraFrameworkProtos.SlaveStatusDetails.StatusDetailsType.EXECUTOR_METADATA)
                    .setExecutorMetadata(CassandraFrameworkProtos.ExecutorMetadata.newBuilder()
                        .setExecutorId(executorIdValue(taskInfo))
                        .setIp("NO_IP!!!"))
                    .build().toByteString())
                .build());
    }

    private void executorTaskFinished(Protos.TaskInfo taskInfo, CassandraFrameworkProtos.SlaveStatusDetails slaveStatusDetails) {
        scheduler.statusUpdate(driver, Protos.TaskStatus.newBuilder()
                .setExecutorId(executorId(taskInfo))
                .setHealthy(true)
                .setSlaveId(taskInfo.getSlaveId())
                .setSource(Protos.TaskStatus.Source.SOURCE_EXECUTOR)
                .setTaskId(taskInfo.getTaskId())
                .setTimestamp(System.currentTimeMillis())
                .setState(Protos.TaskState.TASK_FINISHED)
                .setData(slaveStatusDetails.toByteString())
                .build());
    }

    private void sendHealthCheckResult(Protos.TaskInfo taskInfo, CassandraFrameworkProtos.HealthCheckDetails healthCheckDetails) {
        scheduler.frameworkMessage(driver, executorId(taskInfo), taskInfo.getSlaveId(),
            CassandraFrameworkProtos.SlaveStatusDetails.newBuilder()
                .setStatusDetailsType(CassandraFrameworkProtos.SlaveStatusDetails.StatusDetailsType.HEALTH_CHECK_DETAILS)
                .setHealthCheckDetails(healthCheckDetails).build().toByteArray());
    }

    private Protos.TaskInfo launchExecutor(Tuple2<Protos.SlaveID, String> slave, int nodeCount) throws InvalidProtocolBufferException {
        Protos.Offer offer = createOffer(slave);

        scheduler.resourceOffers(driver, Collections.singletonList(offer));

        Tuple2<Collection<Protos.OfferID>, Collection<Protos.TaskInfo>> launchTasks = driver.launchTasks();
        assertTrue(driver.declinedOffers().isEmpty());
        assertTrue(driver.submitTasks().isEmpty());

        assertEquals(nodeCount, cluster.getClusterState().get().getNodesCount());
        assertEquals(1, launchTasks._2.size());

        Protos.TaskInfo taskInfo = launchTasks._2.iterator().next();

        CassandraFrameworkProtos.TaskDetails taskDetails = taskDetails(taskInfo);
        assertEquals(CassandraFrameworkProtos.TaskDetails.TaskType.EXECUTOR_METADATA, taskDetails.getTaskType());
        return taskInfo;
    }

    private Protos.TaskInfo launchTaskOnAny(CassandraFrameworkProtos.TaskDetails.TaskType taskType) throws InvalidProtocolBufferException {
        for (Tuple2<Protos.SlaveID, String> slave : slaves) {
            Protos.Offer offer = createOffer(slave);

            scheduler.resourceOffers(driver, Collections.singletonList(offer));

            Tuple2<Collection<Protos.OfferID>, Collection<Protos.TaskInfo>> launchTasks = driver.launchTasks();
            if (!driver.declinedOffers().isEmpty())
                continue;

            assertEquals(1, launchTasks._2.size());
            assertTrue(driver.submitTasks().isEmpty());

            Protos.TaskInfo taskInfo = launchTasks._2.iterator().next();

            CassandraFrameworkProtos.TaskDetails taskDetails = taskDetails(taskInfo);
            assertEquals(taskType, taskDetails.getTaskType());
            return taskInfo;
        }
        return null;
    }

    private CassandraFrameworkProtos.TaskDetails submitTask(Tuple2<Protos.SlaveID, String> slave, CassandraFrameworkProtos.TaskDetails.TaskType taskType) {
        Protos.Offer offer = createOffer(slave);

        scheduler.resourceOffers(driver, Collections.singletonList(offer));

        assertFalse(driver.declinedOffers().isEmpty());
        Tuple2<Collection<Protos.OfferID>, Collection<Protos.TaskInfo>> launchTasks = driver.launchTasks();
        assertTrue(launchTasks._2.isEmpty());
        Collection<Tuple2<Protos.ExecutorID, CassandraFrameworkProtos.TaskDetails>> submitTasks = driver.submitTasks();

        assertEquals(1, submitTasks.size());

        CassandraFrameworkProtos.TaskDetails taskDetails = submitTasks.iterator().next()._2;
        assertEquals(taskType, taskDetails.getTaskType());
        return taskDetails;
    }

    private CassandraFrameworkProtos.TaskDetails launchTask(Tuple2<Protos.SlaveID, String> slave, CassandraFrameworkProtos.TaskDetails.TaskType taskType) throws InvalidProtocolBufferException {
        Protos.Offer offer = createOffer(slave);

        scheduler.resourceOffers(driver, Collections.singletonList(offer));

        assertTrue(driver.declinedOffers().isEmpty());
        Tuple2<Collection<Protos.OfferID>, Collection<Protos.TaskInfo>> launchTasks = driver.launchTasks();
        assertTrue(driver.submitTasks().isEmpty());

        assertEquals(1, launchTasks._2.size());

        Protos.TaskInfo taskInfo = launchTasks._2.iterator().next();

        CassandraFrameworkProtos.TaskDetails taskDetails = taskDetails(taskInfo);
        assertEquals(taskType, taskDetails.getTaskType());
        return taskDetails;
    }

    private static CassandraFrameworkProtos.TaskDetails taskDetails(Protos.TaskInfo data) throws InvalidProtocolBufferException {
        return CassandraFrameworkProtos.TaskDetails.parseFrom(data.getData());
    }

    private void noopOnOffer(Tuple2<Protos.SlaveID, String> slave, int nodeCount) {
        Protos.Offer offer = createOffer(slave);

        scheduler.resourceOffers(driver, Collections.singletonList(offer));

        Tuple2<Collection<Protos.OfferID>, Collection<Protos.TaskInfo>> launchTasks = driver.launchTasks();
        assertTrue(ProtoUtils.protoToString(driver.submitTasks()), driver.submitTasks().isEmpty());
        List<Protos.OfferID> decl = driver.declinedOffers();
        assertThat(decl)
            .hasSize(1)
            .contains(offer.getId());

        assertEquals(nodeCount, cluster.getClusterState().get().getNodesCount());
        assertEquals(0, launchTasks._2.size());
    }

    protected void cleanState() {
        super.cleanState();

        scheduler = new CassandraScheduler(configuration, cluster);

        driver = new MockSchedulerDriver(scheduler);
    }

    private static String executorIdValue(Protos.TaskInfo executorMetadata) {
        return executorId(executorMetadata).getValue();
    }

    private static String taskIdValue(Protos.TaskInfo taskInfo) {
        return taskInfo.getTaskId().getValue();
    }

    private static Protos.ExecutorID executorId(Protos.TaskInfo taskInfo) {
        return taskInfo.getExecutor().getExecutorId();
    }

    private CassandraFrameworkProtos.HealthCheckDetails lastHealthCheckDetails(Protos.TaskInfo executorMetadata) {
        return cluster.lastHealthCheck(executorIdValue(executorMetadata)).getDetails();
    }

}