package com.adobe.ci.aquarium.net;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.adobe.ci.aquarium.fish.client.model.ApplicationState;
import com.adobe.ci.aquarium.fish.client.model.ApplicationStatus;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.remoting.Channel;
import org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout;

/**
 * Class to listen for events in the computer channel and take measures in case anything goes wrong
 */
public class AquariumChannelListener extends Channel.Listener {

    private static final Logger LOG = Logger.getLogger(AquariumChannelListener.class.getName());

    AquariumComputer computer;

    public AquariumChannelListener(AquariumComputer computer) {
        this.computer = computer;
    }

    @Override
    public void onClosed(Channel channel, IOException cause) {
        // The channel was closed - let's find out the reason
        AquariumCloud cloud;
        if( computer == null || computer.getNode() == null ) {
            // There is no node, so nothing is wrong
            LOG.log(Level.FINE, "Channel is closed on computer: " + computer + " cause: " + cause);
            return;
        }
        String app_uid = computer.getAppInfo().getString("ApplicationUID");
        if( app_uid.isEmpty() ) {
            LOG.log(Level.SEVERE, "Unable to locate ApplicationUID for computer: " + computer);
            return;
        }
        ApplicationState state;
        try {
            state = computer.getNode().getAquariumCloud().getClient().applicationStateGet(UUID.fromString(app_uid));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unable to request ApplicationState for ApplicationUID " + app_uid + " reason: " + e);
            return;
        }
        LOG.log(Level.WARNING, "Channel is closed on Computer: " + computer + " with ApplicationUID: " + app_uid + ", State: " + state.getStatus() + ": " + state.getDescription() + ", Cause: " + cause);
        computer.getListener().getLogger().println("AquariumChannelListener remote disconnected: ApplicationUID: " + app_uid + ", State: " + state.getStatus() + ": " + state.getDescription() + ", Cause: " + cause);

        if( state.getStatus() != ApplicationStatus.ALLOCATED ) {
            // Aborting all the executors since the Application was deallocated
            for( Executor exec : computer.getAllExecutors() ) {
                // Using ExceededTimeout here to keep the existing cause timeout detections work as expected
                exec.interrupt(Result.ABORTED, new ExceededTimeout(), new AquariumCauseOfInterruption(state.getStatus(), state.getDescription()));
            }
        }
    }
}
