package com.adobe.ci.aquarium.net.integration;

import com.adobe.ci.aquarium.net.AquariumCloud;
import com.adobe.ci.aquarium.net.config.AquariumCloudConfiguration;
import hudson.model.Result;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import java.util.logging.Level;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import static org.junit.Assert.*;

public class AquariumFishPipelineIT {

    @Rule
    public LoggerRule logger = new LoggerRule().record("com.adobe.ci.aquarium", Level.ALL);

    @Rule
    public AquariumFishTestHelper fishHelper = new AquariumFishTestHelper();

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testPipelineStepImage() throws Exception {
        assertTrue("Fish must be running", fishHelper.isRunning());
        // Create an advanced test user
        fishHelper.createAdvancedTestUser();

        String labelUid = fishHelper.createTestLabel();
        assertNotNull(labelUid);

        AquariumCloudConfiguration config = fishHelper.getPluginConfig(j);
        AquariumCloud cloud = new AquariumCloud("pipeline-fish-cloud", config);
        Jenkins jenkins = j.getInstance();
        jenkins.clouds.add(cloud);

        Thread.sleep(3000);
        assertTrue("Cloud should connect", cloud.isConnected());

        // Create a Pipeline job that uses the Aquarium label and calls our steps
        WorkflowJob job = j.createProject(WorkflowJob.class, "fish-pipeline");

        String jenkinsfile = "" +
                "pipeline {\n" +
                "  agent none\n" +
                "  stages {\n" +
                "    stage('Agent execution') {\n" +
                "      agent { label 'jenkins-test-label' }\n" +
                "      stages {\n" +
                "        stage('App Info') {\n" +
                "          steps {\n" +
                "            script {\n" +
                "              def info = aquariumApplicationInfo()\n" +
                "              echo " + '"' + "App info: ${info}" + '"' + "\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "        stage('Create Tasks On Agent') {\n" +
                "          steps {\n" +
                "            script {\n" +
                "              def t1 = aquariumCreateImage(full: false, when: 'DEALLOCATE')\n" +
                "              echo " + '"' + "Image task UID: ${t1}" + '"' + "\n" +
                "              env.IMG_TASK_UID = t1\n" +
                "              // query image task without waiting (still on agent)\n" +
                "              def t1data = aquariumApplicationTask(taskUid: t1, wait: false)\n" +
                "              echo " + '"' + "Image task data: ${t1data}" + '"' + "\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    stage('Query Task Outside Node') {\n" +
                "      steps {\n" +
                "        script {\n" +
                "          def t1dataWait = aquariumApplicationTask(taskUid: env.IMG_TASK_UID, wait: true)\n" +
                "          echo " + '"' + "Image task data (waited): ${t1dataWait}" + '"' + "\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        job.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition(jenkinsfile, true));

        WorkflowRun build = j.buildAndAssertSuccess(job);
        assertEquals(Result.SUCCESS, build.getResult());

        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("Running on Aquarium Fish node: fish-"));
        assertTrue("App info must be echoed", log.contains("App info:"));
        assertTrue("Image task UID must be echoed", log.contains("Image task UID:"));
        assertTrue("ApplicationTask data must be echoed", log.contains("Image task data:"));
    }

    @Test
    public void testPipelineStepSnapshot() throws Exception {
        assertTrue("Fish must be running", fishHelper.isRunning());
        // Create an advanced test user
        fishHelper.createAdvancedTestUser();

        String labelUid = fishHelper.createTestLabel();
        assertNotNull(labelUid);

        AquariumCloudConfiguration config = fishHelper.getPluginConfig(j);
        AquariumCloud cloud = new AquariumCloud("pipeline-fish-cloud", config);
        Jenkins jenkins = j.getInstance();
        jenkins.clouds.add(cloud);

        Thread.sleep(3000);
        assertTrue("Cloud should connect", cloud.isConnected());

        // Create a Pipeline job that uses the Aquarium label and calls our steps
        WorkflowJob job = j.createProject(WorkflowJob.class, "fish-pipeline");

        String jenkinsfile = "" +
                "pipeline {\n" +
                "  agent none\n" +
                "  stages {\n" +
                "    stage('Agent execution') {\n" +
                "      agent { label 'jenkins-test-label' }\n" +
                "      stages {\n" +
                "        stage('App Info') {\n" +
                "          steps {\n" +
                "            script {\n" +
                "              def info = aquariumApplicationInfo()\n" +
                "              echo " + '"' + "App info: ${info}" + '"' + "\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "        stage('Create Tasks On Agent') {\n" +
                "          steps {\n" +
                "            script {\n" +
                "              def t1 = aquariumCreateSnapshot(full: false, when: 'DEALLOCATE')\n" +
                "              echo " + '"' + "Snapshot task UID: ${t1}" + '"' + "\n" +
                "              env.SNP_TASK_UID = t1\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    stage('Query Task Outside Node') {\n" +
                "      steps {\n" +
                "        script {\n" +
                "          def t1dataWait = aquariumApplicationTask(taskUid: env.SNP_TASK_UID, wait: true)\n" +
                "          echo " + '"' + "Snapshot task data (waited): ${t1dataWait}" + '"' + "\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        job.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition(jenkinsfile, true));

        WorkflowRun build = j.buildAndAssertSuccess(job);
        assertEquals(Result.SUCCESS, build.getResult());

        String log = JenkinsRule.getLog(build);
        assertTrue(log.contains("Running on Aquarium Fish node: fish-"));
        assertTrue("App info must be echoed", log.contains("App info:"));
        assertTrue("Snapshot task UID must be echoed", log.contains("Snapshot task UID:"));
        assertTrue("ApplicationTask data must be echoed", log.contains("Snapshot task data:"));
    }
}


