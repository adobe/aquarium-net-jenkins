/**
 * Copyright 2024-2025 Adobe. All rights reserved.
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

package com.adobe.ci.aquarium.net;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import aquarium.v2.ApplicationOuterClass.ApplicationState.Status;
import com.adobe.ci.aquarium.net.model.ApplicationState;
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
        if( computer == null || computer.getNode() == null ) {
            // There is no node, so nothing is wrong
            LOG.log(Level.FINE, "Channel is closed on computer: " + computer + " cause: " + cause);
            return;
        }
        if (computer.getAppInfo() == null) {
            LOG.log(Level.WARNING, "AppInfo is null for computer: " + computer);
            return;
        }
        String app_uid = computer.getAppInfo().optString("ApplicationUID", "");
        if( app_uid == null || app_uid.isEmpty() ) {
            LOG.log(Level.SEVERE, "Unable to locate ApplicationUID for computer: " + computer);
            return;
        }
        ApplicationState state;
        try {
            AquariumSlave node = computer.getNode();
            if (node == null || node.getAquariumCloud() == null || node.getAquariumCloud().getClient() == null) {
                LOG.log(Level.WARNING, "Missing node/cloud/client to request state for ApplicationUID " + app_uid + " on computer: " + computer);
                return;
            }
            state = node.getAquariumCloud().getClient().applicationStateGet(UUID.fromString(app_uid));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unable to request ApplicationState for ApplicationUID " + app_uid + " reason: " + e);
            return;
        }
        LOG.log(Level.WARNING, "Channel is closed on Computer: " + computer + " with ApplicationUID: " + app_uid + ", State: " + state.getStatus() + ": " + state.getDescription() + ", Cause: " + cause);
        if (computer.getListener() != null) {
            computer.getListener().getLogger().println("AquariumChannelListener remote disconnected: ApplicationUID: " + app_uid + ", State: " + state.getStatus() + ": " + state.getDescription() + ", Cause: " + cause);
        }

        if( state.getStatus() != Status.ALLOCATED ) {
            // Aborting all the executors since the Application was deallocated
            for( Executor exec : computer.getAllExecutors() ) {
                // Using ExceededTimeout here to keep the existing cause timeout detections work as expected
                exec.interrupt(Result.ABORTED, new ExceededTimeout(), new AquariumCauseOfInterruption(state.getStatus(), state.getDescription()));
            }
        }
    }
}
