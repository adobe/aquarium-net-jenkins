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
import com.adobe.ci.aquarium.net.config.AquariumLabelTemplate;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import java.util.logging.Level;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class AquariumCreateLabelStepIT {

    @Rule
    public LoggerRule logger = new LoggerRule().record("com.adobe.ci.aquarium", Level.ALL);

    @Rule
    public AquariumFishTestHelper fishHelper = new AquariumFishTestHelper();

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testCreateLabelPipelineStep() throws Exception {
        fishHelper.startFishNode(null);

        assertTrue("Fish must be running", fishHelper.isRunning());
        // Create an advanced test user
        fishHelper.createAdvancedTestUser();

        String labelUid = fishHelper.createTestLabel();
        assertNotNull(labelUid);

        // Create label templates for testing
        List<AquariumLabelTemplate> templates = createTestLabelTemplates();

        AquariumCloudConfiguration config = fishHelper.getPluginConfig(j);
        AquariumCloudConfiguration configWithTemplates = new AquariumCloudConfiguration.Builder()
                .enabled(config.isEnabled())
                .initAddress(config.getInitAddress())
                .credentialsId(config.getCredentialsId())
                .certificateId(config.getCertificateId())
                .agentConnectionWaitMinutes(config.getAgentConnectionWaitMinutes())
                .jenkinsUrl(config.getJenkinsUrl())
                .additionalMetadata(config.getAdditionalMetadata())
                .labelFilter(config.getLabelFilter())
                .labelTemplates(templates)
                .build();

        AquariumCloud cloud = new AquariumCloud("create-label-test-cloud", configWithTemplates);
        Jenkins jenkins = j.getInstance();
        jenkins.clouds.add(cloud);

        Thread.sleep(3000);
        assertTrue("Cloud should connect", cloud.isConnected());

        // Create a Pipeline job that uses the aquariumCreateLabel step
        WorkflowJob job = j.createProject(WorkflowJob.class, "create-label-pipeline");

        String jenkinsfile = "" +
                "pipeline {\n" +
                "  agent none\n" +
                "  stages {\n" +
                "    stage('Create Label Test') {\n" +
                "      agent { label 'jenkins-test-label' }\n" +
                "      stages {\n" +
                "        stage('Create Label with Variables') {\n" +
                "          steps {\n" +
                "            script {\n" +
                "              def labelUid = aquariumCreateLabel(\n" +
                "                templateId: 'test-template-1',\n" +
                "                variables: [\n" +
                "                  [key: 'name', value: 'integration-test'],\n" +
                "                  [key: 'image', value: 'ami-test123'],\n" +
                "                  [key: 'instance_type', value: 'm5.large']\n" +
                "                ]\n" +
                "              )\n" +
                "              echo " + '"' + "Created label UID: ${labelUid}" + '"' + "\n" +
                "              env.CREATED_LABEL_UID = labelUid\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "        stage('Create Simple Label') {\n" +
                "          steps {\n" +
                "            script {\n" +
                "              def simpleUid = aquariumCreateLabel(\n" +
                "                templateId: 'simple-template'\n" +
                "              )\n" +
                "              echo " + '"' + "Created simple label UID: ${simpleUid}" + '"' + "\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    stage('Verify Creation') {\n" +
                "      steps {\n" +
                "        script {\n" +
                "          echo " + '"' + "Label creation completed with UID: ${env.CREATED_LABEL_UID}" + '"' + "\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        job.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition(jenkinsfile, true));

        WorkflowRun build = j.buildAndAssertSuccess(job);

        String log = JenkinsRule.getLog(build);
        System.out.println("Log: " + log);

        assertTrue("Log must contain 'Running on fish-'", log.contains("Running on fish-"));
        assertTrue("Created label UID must be echoed", log.contains("Created label UID:"));
        assertTrue("Created simple label UID must be echoed", log.contains("Created simple label UID:"));
        assertTrue("Label creation completed must be echoed", log.contains("Label creation completed with UID:"));
    }

    @Test
    public void testCreateLabelWithMissingVariable() throws Exception {
        fishHelper.startFishNode(null);

        assertTrue("Fish must be running", fishHelper.isRunning());
        fishHelper.createAdvancedTestUser();

        String labelUid = fishHelper.createTestLabel();
        assertNotNull(labelUid);

        // Create label templates for testing
        List<AquariumLabelTemplate> templates = createTestLabelTemplates();

        AquariumCloudConfiguration config = fishHelper.getPluginConfig(j);
        AquariumCloudConfiguration configWithTemplates = new AquariumCloudConfiguration.Builder()
                .enabled(config.isEnabled())
                .initAddress(config.getInitAddress())
                .credentialsId(config.getCredentialsId())
                .certificateId(config.getCertificateId())
                .agentConnectionWaitMinutes(config.getAgentConnectionWaitMinutes())
                .jenkinsUrl(config.getJenkinsUrl())
                .additionalMetadata(config.getAdditionalMetadata())
                .labelFilter(config.getLabelFilter())
                .labelTemplates(templates)
                .build();

        AquariumCloud cloud = new AquariumCloud("create-label-test-cloud-fail", configWithTemplates);
        Jenkins jenkins = j.getInstance();
        jenkins.clouds.add(cloud);

        Thread.sleep(3000);
        assertTrue("Cloud should connect", cloud.isConnected());

        // Create a Pipeline job that should fail due to missing variable
        WorkflowJob job = j.createProject(WorkflowJob.class, "create-label-pipeline-fail");

        String jenkinsfile = "" +
                "pipeline {\n" +
                "  agent none\n" +
                "  stages {\n" +
                "    stage('Create Label Test - Should Fail') {\n" +
                "      agent { label 'jenkins-test-label' }\n" +
                "      stages {\n" +
                "        stage('Create Label with Missing Variable') {\n" +
                "          steps {\n" +
                "            script {\n" +
                "              try {\n" +
                "                def labelUid = aquariumCreateLabel(\n" +
                "                  templateId: 'test-template-1',\n" +
                "                  variables: [\n" +
                "                    [key: 'name', value: 'integration-test']\n" +
                "                    // Missing 'image' and 'instance_type' variables\n" +
                "                  ]\n" +
                "                )\n" +
                "                error('Should have failed due to missing variables')\n" +
                "              } catch (Exception e) {\n" +
                "                echo " + '"' + "Expected failure: ${e.message}" + '"' + "\n" +
                "                if (e.message.contains('Unresolved variable')) {\n" +
                "                  echo 'Test passed: Missing variable error detected'\n" +
                "                } else {\n" +
                "                  throw e\n" +
                "                }\n" +
                "              }\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        job.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition(jenkinsfile, true));

        WorkflowRun build = j.buildAndAssertSuccess(job);

        String log = JenkinsRule.getLog(build);
        System.out.println("Log: " + log);

        assertTrue("Log must contain expected failure message", log.contains("Expected failure:"));
        assertTrue("Log must contain test passed message", log.contains("Test passed: Missing variable error detected"));
    }

    @Test
    public void testCreateLabelWithPredefinedVariables() throws Exception {
        fishHelper.startFishNode(null);

        assertTrue("Fish must be running", fishHelper.isRunning());
        fishHelper.createAdvancedTestUser();

        String labelUid = fishHelper.createTestLabel();
        assertNotNull(labelUid);

        // Create label templates for testing
        List<AquariumLabelTemplate> templates = createTestLabelTemplates();

        AquariumCloudConfiguration config = fishHelper.getPluginConfig(j);
        AquariumCloudConfiguration configWithTemplates = new AquariumCloudConfiguration.Builder()
                .enabled(config.isEnabled())
                .initAddress(config.getInitAddress())
                .credentialsId(config.getCredentialsId())
                .certificateId(config.getCertificateId())
                .agentConnectionWaitMinutes(config.getAgentConnectionWaitMinutes())
                .jenkinsUrl(config.getJenkinsUrl())
                .additionalMetadata(config.getAdditionalMetadata())
                .labelFilter(config.getLabelFilter())
                .labelTemplates(templates)
                .build();

        AquariumCloud cloud = new AquariumCloud("create-label-test-cloud-predefined", configWithTemplates);
        Jenkins jenkins = j.getInstance();
        jenkins.clouds.add(cloud);

        Thread.sleep(3000);
        assertTrue("Cloud should connect", cloud.isConnected());

        // Create a Pipeline job that uses predefined variables
        WorkflowJob job = j.createProject(WorkflowJob.class, "create-label-pipeline-predefined");

        String jenkinsfile = "" +
                "pipeline {\n" +
                "  agent none\n" +
                "  stages {\n" +
                "    stage('Create Label with Predefined Variables') {\n" +
                "      agent { label 'jenkins-test-label' }\n" +
                "      stages {\n" +
                "        stage('Create Label with Timestamp') {\n" +
                "          steps {\n" +
                "            script {\n" +
                "              def labelUid = aquariumCreateLabel(\n" +
                "                templateId: 'timestamp-template'\n" +
                "              )\n" +
                "              echo " + '"' + "Created timestamp label UID: ${labelUid}" + '"' + "\n" +
                "            }\n" +
                "          }\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        job.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition(jenkinsfile, true));

        WorkflowRun build = j.buildAndAssertSuccess(job);

        String log = JenkinsRule.getLog(build);
        System.out.println("Log: " + log);

        assertTrue("Log must contain 'Running on fish-'", log.contains("Running on fish-"));
        assertTrue("Created timestamp label UID must be echoed", log.contains("Created timestamp label UID:"));
    }

    private List<AquariumLabelTemplate> createTestLabelTemplates() {
        List<AquariumLabelTemplate> templates = new ArrayList<>();

        // Template 1: Basic template with variables
        String template1Content = "" +
                "name: test-${name}-${TIMESTAMP}\n" +
                "version: 0\n" +
                "visible_for:\n" +
                "  - jenkins\n" +
                "  - jenkins-group\n" +
                "remove_at: '${NOW+1*HOUR}'\n" +
                "definitions:\n" +
                "  - driver: aws\n" +
                "    images:\n" +
                "      - name: '${image}'\n" +
                "    options:\n" +
                "      instance_type: '${instance_type}'\n" +
                "      security_groups:\n" +
                "        - test-group\n" +
                "      userdata_format: env\n" +
                "    resources:\n" +
                "      cpu: 2\n" +
                "      ram: 4\n" +
                "      network: Subnet:test\n" +
                "      lifetime: 2h\n" +
                "metadata:\n" +
                "  TEST_MODE: 'true'\n" +
                "  JENKINS_AGENT_WORKSPACE: /tmp/ws";

        AquariumLabelTemplate template1 = new AquariumLabelTemplate(
                "test-template-1",
                "Test Template 1",
                template1Content
        );
        template1.setDescription("Template for testing with variables");
        templates.add(template1);

        // Template 2: Simple template without variables
        String template2Content = "" +
                "name: simple-test-label\n" +
                "version: 0\n" +
                "visible_for:\n" +
                "  - jenkins\n" +
                "definitions:\n" +
                "  - driver: local\n" +
                "    resources:\n" +
                "      cpu: 1\n" +
                "      ram: 2\n" +
                "metadata:\n" +
                "  SIMPLE_MODE: 'true'";

        AquariumLabelTemplate template2 = new AquariumLabelTemplate(
                "simple-template",
                "Simple Test Template",
                template2Content
        );
        template2.setDescription("Simple template without variables");
        templates.add(template2);

        // Template 3: Template with predefined variables
        String template3Content = "" +
                "name: timestamp-test-${TIMESTAMP}\n" +
                "version: 0\n" +
                "visible_for:\n" +
                "  - jenkins\n" +
                "remove_at: '${NOW+2*HOUR}'\n" +
                "definitions:\n" +
                "  - driver: test\n" +
                "    resources:\n" +
                "      cpu: 1\n" +
                "      ram: 1\n" +
                "      lifetime: 1h\n" +
                "metadata:\n" +
                "  CREATED_AT: '${TIMESTAMP}'";

        AquariumLabelTemplate template3 = new AquariumLabelTemplate(
                "timestamp-template",
                "Timestamp Test Template",
                template3Content
        );
        template3.setDescription("Template using predefined variables");
        templates.add(template3);

        return templates;
    }
}
