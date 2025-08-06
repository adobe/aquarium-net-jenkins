/**
 * Copyright 2021-2025 Adobe. All rights reserved.
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

import com.google.common.base.Throwables;
import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

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
        return !this.launched;
    }

    @Override
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

        LOG.log(Level.INFO, "Launch node " + comp.getName());

        String nodeFirstLabel = node.getLabelString().split(" ")[0];

        try {
            AquariumCloud cloud = node.getAquariumCloud();
            cloud.startApplicationCreation(node, nodeFirstLabel);

            listener.getLogger().println("Starting Aquarium agent launch process...");

            // In the new architecture, application creation and monitoring happens in AquariumCloud
            // The launcher just waits for the application to be allocated and sets up the agent

            if (!this.launched) {
                // Set up basic application info for display
                JSONObject app_info = new JSONObject();
                UUID applicationUID = node.getApplicationUID();
                if (applicationUID != null) {
                    app_info.put("ApplicationUID", applicationUID.toString());
                    app_info.put("Status", "Provisioning");
                    comp.setAppInfo(app_info);
                    listener.getLogger().println("Aquarium Application UID: " + applicationUID);
                }

                // Wait for agent connection with configured timeout
                int maxWaitMinutes = cloud.getAgentConnectWaitMin();
                int maxWaitSeconds = maxWaitMinutes * 60;
                int waitedSeconds = 0;

                listener.getLogger().println("Waiting for agent connection (timeout: " + maxWaitMinutes + " minutes)...");

                while (waitedSeconds < maxWaitSeconds) {
                    SlaveComputer slaveComputer = node.getComputer();
                    if (slaveComputer == null) {
                        throw new IllegalStateException("Node was deleted during launch");
                    }

                    if (slaveComputer.isOnline()) {
                        listener.getLogger().println("Agent connected successfully!");
                        break;
                    }

                    // Sleep and increment counter
                    Thread.sleep(5000);
                    waitedSeconds += 5;

                    // Log progress every minute
                    if (waitedSeconds % 60 == 0) {
                        int minutesWaited = waitedSeconds / 60;
                        listener.getLogger().println("Still waiting for agent connection... (" + minutesWaited + "/" + maxWaitMinutes + " minutes)");
                    }
                }

                // Check final connection status
                SlaveComputer finalComputer = node.getComputer();
                if (finalComputer == null || finalComputer.isOffline()) {
                    listener.getLogger().println("Agent failed to connect within timeout, terminating...");
                    throw new IllegalStateException("Agent connection timeout exceeded");
                }

                // Set up the retention strategy for one-time use
                node.setRetentionStrategy(new OnceRetentionStrategy(5));
                listener.getLogger().println("Agent configured for one-time use");

                // Add channel listener for interruption handling
                if (computer.getChannel() != null) {
                    computer.getChannel().addListener(new AquariumChannelListener(comp));
                    listener.getLogger().println("Channel listener configured");
                } else {
                    LOG.log(Level.WARNING, "Unable to set channel listener - channel is null");
                }

                // Enable task acceptance
                computer.setAcceptingTasks(true);
                this.launched = true;

                // Persist the launched state
                try {
                    node.save();
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not save agent state: " + e.getMessage(), e);
                }

                listener.getLogger().println("Aquarium agent launch completed successfully!");

            } else {
                listener.getLogger().println("Agent already launched, skipping setup...");
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
