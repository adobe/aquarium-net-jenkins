package com.adobe.ci.aquarium.net;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.adobe.ci.aquarium.fish.client.model.Application;
import com.adobe.ci.aquarium.fish.client.model.ApplicationState;
import com.google.common.base.Throwables;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.kohsuke.stapler.DataBoundConstructor;
import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;

import javax.annotation.CheckForNull;

public class AquariumLauncher extends JNLPLauncher {

    private static final Logger LOG = Logger.getLogger(AquariumLauncher.class.getName());

    private boolean launched;

    @CheckForNull
    private transient Throwable problem;

    @DataBoundConstructor
    public AquariumLauncher(String tunnel, String vmargs) {
        super(tunnel, vmargs);
    }

    @Override
    public boolean isLaunchSupported() {
        return !launched;
    }

    @Override
    @SuppressFBWarnings(value = "SWL_SLEEP_WITH_LOCK_HELD", justification = "This is fine")
    public synchronized void launch(SlaveComputer computer, TaskListener listener) {
        if (!(computer instanceof AquariumComputer)) {
            throw new IllegalArgumentException("This Launcher can be used only with AquariumComputer");
        }
        AquariumComputer comp = (AquariumComputer) computer;
        computer.setAcceptingTasks(false);
        AquariumSlave node = comp.getNode();
        if (node == null) {
            throw new IllegalStateException("Node has been removed, cannot launch " + computer.getName());
        }

        LOG.log(Level.INFO, "Launch node" + comp.getName());

        try {
            // Request for resource
            AquariumCloud cloud = node.getAquariumCloud();
            AquariumClient client = cloud.getClient();
            Application app = client.applicationCreate(
                    node.getLabelString(),
                    cloud.getJenkinsUrl(),
                    node.getNodeName(),
                    comp.getJnlpMac()
            );

            node.setApplicationId(app.getID());

            // Wait for agent connection
            SlaveComputer slaveComputer;
            ApplicationState status = client.applicationStateGet(app.getID());
            while( true ) {
                slaveComputer = node.getComputer();
                if( slaveComputer == null ) {
                    throw new IllegalStateException("Node was deleted, computer is null");
                }
                if( slaveComputer.isOnline() ) {
                    break;
                }

                // Check that the resource hasn't failed already
                status = client.applicationStateGet(app.getID());
                if( status.getStatus() == ApplicationState.StatusEnum.ERROR ) {
                    // Resource launch failed
                    LOG.log(Level.WARNING, "Unable to allocate resource:" + status.getDescription() + ", node:" + comp.getName());
                    break;
                }

                Thread.sleep(5000);
            }
            if( slaveComputer.isOffline() ) {
                throw new IllegalStateException("Agent is not connected, status:" + status.toString());
            }

            computer.setAcceptingTasks(true);
            launched = true;

            try {
                node.save(); // We need to persist the "launched" setting...
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not save() agent: " + e.getMessage(), e);
            }
        } catch (Throwable ex) {
            setProblem(ex);
            LOG.log(Level.WARNING, String.format("Error in provisioning; agent=%s", node), ex);
            LOG.log(Level.FINER, "Removing Jenkins node: {0}", node.getNodeName());
            try {
                node.terminate();
            } catch (IOException | InterruptedException e) {
                LOG.log(Level.WARNING, "Unable to remove Jenkins node", e);
            }
            throw Throwables.propagate(ex);
        }
    }

    @CheckForNull
    public Throwable getProblem() {
        return problem;
    }

    public void setProblem(@CheckForNull Throwable problem) {
        this.problem = problem;
    }
}
