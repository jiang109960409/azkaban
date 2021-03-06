/*
 * Copyright 2014 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.execapp;

import azkaban.event.Event;
import azkaban.event.Event.Type;
import azkaban.event.EventData;
import azkaban.executor.ExecutableFlow;
import azkaban.executor.ExecutableNode;
import azkaban.executor.ExecutorLoader;
import azkaban.executor.JavaJob;
import azkaban.executor.MockExecutorLoader;
import azkaban.executor.SleepJavaJob;
import azkaban.executor.Status;
import azkaban.jobExecutor.ProcessJob;
import azkaban.jobtype.JobTypeManager;
import azkaban.utils.Props;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class JobRunnerTest {

  private final Logger logger = Logger.getLogger("JobRunnerTest");
  private File workingDir;
  private JobTypeManager jobtypeManager;

  public JobRunnerTest() {

  }

  @Before
  public void setUp() throws Exception {
    System.out.println("Create temp dir");
    this.workingDir = new File("_AzkabanTestDir_" + System.currentTimeMillis());
    if (this.workingDir.exists()) {
      FileUtils.deleteDirectory(this.workingDir);
    }
    this.workingDir.mkdirs();
    this.jobtypeManager =
        new JobTypeManager(null, null, this.getClass().getClassLoader());

    this.jobtypeManager.getJobTypePluginSet().addPluginClass("java", JavaJob.class);
  }

  @After
  public void tearDown() throws IOException {
    System.out.println("Teardown temp dir");
    if (this.workingDir != null) {
      FileUtils.deleteDirectory(this.workingDir);
      this.workingDir = null;
    }
  }

  @Ignore
  @Test
  public void testBasicRun() {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 1, false, loader, eventCollector);
    final ExecutableNode node = runner.getNode();

    eventCollector.handleEvent(Event.create(null, Event.Type.JOB_STARTED, new EventData(node)));
    Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED
        || runner.getStatus() != Status.FAILED);

    runner.run();
    eventCollector.handleEvent(Event.create(null, Event.Type.JOB_FINISHED, new EventData(node)));

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue("Node status is " + node.getStatus(),
        node.getStatus() == Status.SUCCEEDED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    Assert.assertTrue(node.getEndTime() - node.getStartTime() > 1000);

    final File logFile = new File(runner.getLogFilePath());
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps != null);
    Assert.assertTrue(logFile.exists());

    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == 3);

    eventCollector.assertEvents(Type.JOB_STARTED, Type.JOB_STATUS_CHANGED, Type.JOB_FINISHED);
  }

  @Ignore
  @Test
  public void testFailedRun() {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 1, true, loader, eventCollector);
    final ExecutableNode node = runner.getNode();

    Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED
        || runner.getStatus() != Status.FAILED);
    runner.run();

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue(node.getStatus() == Status.FAILED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    Assert.assertTrue(node.getEndTime() - node.getStartTime() > 1000);

    final File logFile = new File(runner.getLogFilePath());
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps == null);
    Assert.assertTrue(logFile.exists());
    Assert.assertTrue(eventCollector.checkOrdering());
    Assert.assertTrue(!runner.isKilled());
    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == 3);

    eventCollector.assertEvents(Type.JOB_STARTED, Type.JOB_STATUS_CHANGED, Type.JOB_FINISHED);
  }

  @Test
  public void testDisabledRun() {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 1, false, loader, eventCollector);
    final ExecutableNode node = runner.getNode();

    node.setStatus(Status.DISABLED);

    // Should be disabled.
    Assert.assertTrue(runner.getStatus() == Status.DISABLED);
    runner.run();

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue(node.getStatus() == Status.SKIPPED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    // Give it 10 ms to fail.
    Assert.assertTrue(node.getEndTime() - node.getStartTime() < 10);

    // Log file and output files should not exist.
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps == null);
    Assert.assertTrue(runner.getLogFilePath() == null);
    Assert.assertTrue(eventCollector.checkOrdering());

    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == null);

    eventCollector.assertEvents(Type.JOB_STARTED, Type.JOB_FINISHED);
  }

  @Test
  public void testPreKilledRun() {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 1, false, loader, eventCollector);
    final ExecutableNode node = runner.getNode();

    node.setStatus(Status.KILLED);

    // Should be killed.
    Assert.assertTrue(runner.getStatus() == Status.KILLED);
    runner.run();

    // Should just skip the run and not change
    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue(node.getStatus() == Status.KILLED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    // Give it 10 ms to fail.
    Assert.assertTrue(node.getEndTime() - node.getStartTime() < 10);

    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == null);

    // Log file and output files should not exist.
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps == null);
    Assert.assertTrue(runner.getLogFilePath() == null);
    Assert.assertTrue(!runner.isKilled());
    eventCollector.assertEvents(Type.JOB_STARTED, Type.JOB_FINISHED);
  }

  @Ignore
  @Test
  // todo: HappyRay investigate if it is worth fixing this test. If not, remove it.
  // The change history doesn't mention why this test was ignored.
  public void testCancelRun() throws InterruptedException {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(13, "testJob", 10, false, loader, eventCollector);
    final ExecutableNode node = runner.getNode();

    Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED
        || runner.getStatus() != Status.FAILED);

    final Thread thread = new Thread(runner);
    thread.start();

    Thread.sleep(2000);
    runner.kill();
    Thread.sleep(500);

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue("Status is " + node.getStatus(),
        node.getStatus() == Status.KILLED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    // Give it 10 ms to fail.
    Assert.assertTrue(node.getEndTime() - node.getStartTime() < 3000);
    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == 3);

    // Log file and output files should not exist.
    final File logFile = new File(runner.getLogFilePath());
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps == null);
    Assert.assertTrue(logFile.exists());
    Assert.assertTrue(eventCollector.checkOrdering());
    Assert.assertTrue(runner.isKilled());
    eventCollector.assertEvents(Type.JOB_STARTED, Type.JOB_STATUS_CHANGED, Type.JOB_FINISHED);
  }

  @Ignore
  @Test
  public void testDelayedExecutionJob() {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 1, false, loader, eventCollector);
    runner.setDelayStart(5000);
    final long startTime = System.currentTimeMillis();
    final ExecutableNode node = runner.getNode();

    eventCollector.handleEvent(Event.create(null, Event.Type.JOB_STARTED, new EventData(node)));
    Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED);

    runner.run();
    eventCollector.handleEvent(Event.create(null, Event.Type.JOB_FINISHED, new EventData(node)));

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue("Node status is " + node.getStatus(),
        node.getStatus() == Status.SUCCEEDED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    Assert.assertTrue(node.getEndTime() - node.getStartTime() > 1000);
    Assert.assertTrue(node.getStartTime() - startTime >= 5000);

    final File logFile = new File(runner.getLogFilePath());
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps != null);
    Assert.assertTrue(logFile.exists());
    Assert.assertFalse(runner.isKilled());
    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == 3);

    Assert.assertTrue(eventCollector.checkOrdering());
    eventCollector.assertEvents(Type.JOB_STARTED, Type.JOB_STATUS_CHANGED, Type.JOB_FINISHED);
  }

  @Test
  public void testDelayedExecutionCancelledJob() throws InterruptedException {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 1, false, loader, eventCollector);
    runner.setDelayStart(5000);
    final long startTime = System.currentTimeMillis();
    final ExecutableNode node = runner.getNode();

    eventCollector.handleEvent(Event.create(null, Event.Type.JOB_STARTED, new EventData(node)));
    Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED);

    final Thread thread = new Thread(runner);
    thread.start();

    Thread.sleep(2000);
    runner.kill();
    Thread.sleep(500);

    eventCollector.handleEvent(Event.create(null, Event.Type.JOB_FINISHED, new EventData(node)));

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue("Node status is " + node.getStatus(),
        node.getStatus() == Status.KILLED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    Assert.assertTrue(node.getEndTime() - node.getStartTime() < 1000);
    Assert.assertTrue(node.getStartTime() - startTime >= 2000);
    Assert.assertTrue(node.getStartTime() - startTime <= 5000);
    Assert.assertTrue(runner.isKilled());

    final File logFile = new File(runner.getLogFilePath());
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps == null);
    Assert.assertTrue(logFile.exists());

    Assert.assertEquals(2L, (long) loader.getNodeUpdateCount("testJob"));
    eventCollector.assertEvents(Type.JOB_FINISHED);
  }

  private Props createProps(final int sleepSec, final boolean fail) {
    final Props props = new Props();
    props.put("type", "java");

    props.put(JavaJob.JOB_CLASS, SleepJavaJob.class.getName());
    props.put("seconds", sleepSec);
    props.put(ProcessJob.WORKING_DIR, this.workingDir.getPath());
    props.put("fail", String.valueOf(fail));

    return props;
  }

  private JobRunner createJobRunner(final int execId, final String name, final int time,
      final boolean fail, final ExecutorLoader loader, final EventCollectorListener listener) {
    return createJobRunner(execId, name, time, fail, loader, listener, new Props());
  }

  private JobRunner createJobRunner(final int execId, final String name, final int time,
      final boolean fail, final ExecutorLoader loader, final EventCollectorListener listener,
      final Props azkabanProps) {
    final ExecutableFlow flow = new ExecutableFlow();
    flow.setExecutionId(execId);
    final ExecutableNode node = new ExecutableNode();
    node.setId(name);
    node.setParentFlow(flow);

    final Props props = createProps(time, fail);
    node.setInputProps(props);
    final HashSet<String> proxyUsers = new HashSet<>();
    proxyUsers.add(flow.getSubmitUser());
    final JobRunner runner = new JobRunner(node, this.workingDir, loader, this.jobtypeManager,
        azkabanProps);
    runner.setLogSettings(this.logger, "5MB", 4);

    runner.addListener(listener);
    return runner;
  }

}
