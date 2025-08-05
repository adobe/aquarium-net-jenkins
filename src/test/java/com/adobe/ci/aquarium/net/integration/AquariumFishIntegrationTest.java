package com.adobe.ci.aquarium.net.integration;

import com.adobe.ci.aquarium.net.AquariumCloud;
import com.adobe.ci.aquarium.net.config.AquariumCloudConfiguration;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration test for Aquarium Fish Jenkins plugin.
 * Tests the complete workflow from Fish node startup to Jenkins pipeline execution.
 */
public class AquariumFishIntegrationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public AquariumFishTestHelper fishHelper = new AquariumFishTestHelper();

    @Test
    public void testCompleteWorkflow() throws Exception {
        // Step 1: Fish node is already started by the test helper
        assertTrue("Fish node should be running", fishHelper.isRunning());
        assertNotNull("API endpoint should be available", fishHelper.getApiEndpoint());
        assertNotNull("Admin token should be available", fishHelper.getAdminToken());

        // Step 2: Create test label on Fish node
        String labelUid = fishHelper.createTestLabel();
        assertNotNull("Label UID should not be null", labelUid);

        // Step 3: Configure Jenkins with Aquarium Fish cloud
        AquariumCloudConfiguration config = fishHelper.getPluginConfig(j);
        AquariumCloud cloud = new AquariumCloud("test-fish-cloud", config);

        Jenkins jenkinsInstance = j.getInstance();
        jenkinsInstance.clouds.add(cloud);

        // Wait for cloud to connect
        Thread.sleep(5000);

        // Verify cloud is connected
        assertTrue("Cloud should be connected", cloud.isConnected());
        assertFalse("Fish label cache should not be empty", cloud.getFishLabelsCache().isEmpty());

        // Step 4: Create a simple pipeline job
        FreeStyleProject project = j.createFreeStyleProject("test-fish-job");

        // Configure job to use Fish label
        project.setAssignedLabel(new LabelAtom("jenkins-test-label"));

        // Add a simple build step
        project.getBuildersList().add(new TestBuilder());

        // Step 5: Run the job
        j.buildAndAssertSuccess(project);

        // Step 6: Verify job completed successfully
        assertNotNull("Build should exist", project.getLastBuild());
        assertEquals("Build should be successful", Result.SUCCESS, project.getLastBuild().getResult());

        // Step 7: Wait for node cleanup (should happen automatically)
        Thread.sleep(10000);

        // Verify no Fish nodes remain in Jenkins
        int fishNodeCount = 0;
        for (hudson.model.Node node : jenkinsInstance.getNodes()) {
            if (node.getNodeName().startsWith("fish-")) {
                fishNodeCount++;
            }
        }

        assertEquals("No Fish nodes should remain after job completion", 0, fishNodeCount);
    }

    @Test
    public void testCloudConnection() throws Exception {
        // Test cloud connection and label discovery
        AquariumCloudConfiguration config = fishHelper.getPluginConfig(j);
        AquariumCloud cloud = new AquariumCloud("connection-test", config);

        Jenkins jenkinsInstance = j.getInstance();
        jenkinsInstance.clouds.add(cloud);

        // Wait for connection
        Thread.sleep(5000);

        String labelUid = fishHelper.createTestLabel();

        // Wait for label sync
        Thread.sleep(2000);


        assertTrue("Cloud should be connected", cloud.isConnected());
        assertFalse("Fish label cache should not be empty", cloud.getFishLabelsCache().isEmpty());

        // Verify our test label is available
        boolean foundTestLabel = false;
        for (String labelName : cloud.getFishLabelsCache().keySet()) {
            if ("jenkins-test-label".equals(cloud.getFishLabelsCache().get(labelName).getName())) {
                foundTestLabel = true;
                break;
            }
        }
        assertTrue("Test label should be available in cache: " + cloud.getFishLabelsCache().keySet(), foundTestLabel);
    }

    @Test
    public void testLabelProvisioning() throws Exception {
        // Test that the cloud can provision nodes for our label
        AquariumCloudConfiguration config = fishHelper.getPluginConfig(j);
        AquariumCloud cloud = new AquariumCloud("provisioning-test", config);

        Jenkins jenkinsInstance = j.getInstance();
        jenkinsInstance.clouds.add(cloud);

        // Wait for connection
        Thread.sleep(5000);

        String labelUid = fishHelper.createTestLabel();

        // Wait for label sync
        Thread.sleep(2000);

        // Create label atom for testing
        LabelAtom labelAtom = new LabelAtom("jenkins-test-label");

        // Test canProvision
        assertTrue("Cloud should be able to provision for test label",
                  cloud.canProvision(labelAtom));

        // Test provision method
        NodeProvisioner.PlannedNode plannedNode = cloud.provision(labelAtom, 1).iterator().next();
        assertNotNull("Should return a planned node", plannedNode);
        assertTrue("Planned node name should start with 'fish-'",
                  plannedNode.displayName.startsWith("fish-"));
    }

    /**
     * Simple test builder that just logs a message
     */
    private static class TestBuilder extends hudson.tasks.Builder {
        @Override
        public hudson.tasks.BuildStepMonitor getRequiredMonitorService() {
            return hudson.tasks.BuildStepMonitor.NONE;
        }

        @Override
        public boolean perform(hudson.model.AbstractBuild<?, ?> build, hudson.Launcher launcher,
                             hudson.model.BuildListener listener) throws InterruptedException, IOException {
            listener.getLogger().println("Running on Aquarium Fish node: " + build.getBuiltOn().getNodeName());
            listener.getLogger().println("Node description: " + build.getBuiltOn().getNodeDescription());
            return true;
        }
    }
}
