/**
 * Copyright 2025 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

// Author: Sergei Parshev (@sparshev)

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
import java.util.List;
import hudson.model.Cause;

import static org.junit.Assert.*;

public class AquariumFishPipelineWorkerTimeoutIT {

    @Rule
    public LoggerRule logger = new LoggerRule().record("com.adobe.ci.aquarium", Level.ALL);

    @Rule
    public AquariumFishTestHelper fishHelper = new AquariumFishTestHelper();

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testPipelineWorkerTimeout() throws Exception {
        fishHelper.startFishNode(
            "node_location: test_loc\n" +
            "api_address: 127.0.0.1:0\n" +
            "default_resource_lifetime: 10s\n" +
            "drivers:\n" +
            "  gates: {}\n" +
            "  providers:\n" +
            "    docker:\n" +
            "      ignore_non_controlled: true\n");

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
                "        stage('Wait 15s for worker timeout') {\n" +
                "          steps {\n" +
                "            sh 'sleep 5'\n" +
                "            sh 'echo OK'\n" +
                "            sh 'sleep 5'\n" +
                "            sh 'echo OK'\n" +
                "            sh 'sleep 5'\n" +
                "            sh 'echo NOT_OK'\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        job.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition(jenkinsfile, true));

        WorkflowRun build = j.buildAndAssertStatus(Result.ABORTED, job);

        String log = JenkinsRule.getLog(build);
        System.out.println("Log: " + log);


        // Verify that our custom interruption message made it to the console
        boolean hasAquariumMsg = log.contains("Interrupted by Aquarium") ||
                                 log.contains("AquariumChannelListener remote disconnected");
        assertTrue("Console log should include Aquarium interruption message", hasAquariumMsg);

        List<Cause> causes = build.getCauses();
        System.out.println("Cause of interruption: " + causes.size());
        for (Cause cause : causes) {
            System.out.println("Cause: " + cause.toString());
            System.out.println("Checking causes: " + causes.size());
            /*if (cause instanceof CauseOfInterruption) {
                System.out.println("Cause of interruption: " + cause.getShortDescription());
            }*/
        }

        assertTrue("Log must contain 'Running on fish-'", log.contains("Running on fish-"));
        /*assertTrue("App info must be echoed", log.contains("App info:"));
        assertTrue("Image task UID must be echoed", log.contains("Image task UID:"));
        assertTrue("ApplicationTask data must be echoed", log.contains("Image task data:"));
        assertTrue("Image task options 'full' value must be echoed", log.contains("Image task options 'full' value: false"));
        assertTrue("Image task result image name must be echoed", log.contains("Image task result image name: fish-"));*/
    }
}


