/**
 * Copyright 2021 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.adobe.ci.aquarium.net;

import com.adobe.ci.aquarium.fish.client.ApiException;
import com.adobe.ci.aquarium.fish.client.model.*;
import com.google.common.base.Throwables;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.CheckForNull;
import java.io.IOException;
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

        LOG.log(Level.INFO, "Launch node " + comp.getName());

        String nodeFirstLabel = node.getLabelString().split(" ")[0];
        try {
            // Request for resource
            AquariumCloud cloud = node.getAquariumCloud();
            AquariumClient client = cloud.getClient();

            SlaveComputer slaveComputer;
            Label label = null;
            Application app;
            ApplicationState state = null;

            // Checking if label contains version
            int colonPos = nodeFirstLabel.indexOf(':');
            if (colonPos > 0) {
                // Label name contains version, so getting the required label version

                String labelName = nodeFirstLabel.substring(0, colonPos);
                Integer labelVersion = Integer.parseInt(nodeFirstLabel.substring(colonPos + 1));
                label = client.labelVersionFind(labelName, labelVersion);
            } else {
                // No version in the label, so using latest one
                label = client.labelFindLatest(nodeFirstLabel);
            }
            if (label == null) {
                throw new IllegalStateException("Label was not found in Aquarium: " + nodeFirstLabel);
            }

            // If jenkins master restarted with already launched node - we should not create a new Application and just
            // to wait when the agent will connect back or if it will not connect - terminate the node.
            if( !this.launched ) {

                // Since the Application was not requested - requesting a new one
                if( node.getApplicationUID() == null ) {
                    app = client.applicationCreate(
                            label.getUID(),
                            cloud.getJenkinsUrl(),
                            node.getNodeName(),
                            comp.getJnlpMac(),
                            cloud.getMetadata()
                    );

                    node.setApplicationUID(app.getUID());
                    listener.getLogger().println("Aquarium Application was requested: " + app.getUID() + " with Label: " + label.getName() + ":" + label.getVersion());
                } else {
                    app = client.applicationGet(node.getApplicationUID());
                    listener.getLogger().println("Aquarium Application already exist: " + app.getUID() + " with Label: " + label.getName() + ":" + label.getVersion());
                }
                // Notify computer log that the request for Application was sent
                JSONObject app_info = new JSONObject();
                app_info.put("ApplicationUID", app.getUID().toString());
                app_info.put("LabelName", label.getName());
                app_info.put("LabelVersion", label.getVersion());
                comp.setAppInfo(app_info);

                // Wait for fish node election process - it could take a while if there is not enough resources in the pool
                int wait_in_elected = 60; // 60 * 5 - status_call_time >= 5 mins
                while (true) {
                    slaveComputer = node.getComputer();
                    if (slaveComputer == null) {
                        throw new IllegalStateException("Node was deleted, computer is null");
                    }
                    if (slaveComputer.isOnline()) {
                        break;
                    }

                    // Check that the resource hasn't failed already
                    try {
                        state = client.applicationStateGet(app.getUID());
                        if (state.getStatus() == ApplicationStatus.ALLOCATED) {
                            break;
                        } else if (state.getStatus() == ApplicationStatus.ELECTED) {
                            // Application should not be in elected state for too long
                            wait_in_elected--;
                            if (wait_in_elected < 0) {
                                // Wait for elected failed
                                LOG.log(Level.WARNING, "Application stuck in ELECTED state for too long:" + state.getDescription() + ", node:" + comp.getName());
                                break;
                            }
                        } else if (state.getStatus() != ApplicationStatus.ELECTED && state.getStatus() != ApplicationStatus.NEW) {
                            // Resource launch failed
                            LOG.log(Level.WARNING, "Unable to get resource from pool:" + state.getDescription() + ", node:" + comp.getName());
                            break;
                        }
                    } catch (ApiException e) {
                        LOG.log(Level.WARNING, "Error happened during API request:" + e + ", node:" + comp.getName());
                    }

                    Thread.sleep(5000);
                }
            } else {
                listener.getLogger().println("Aquarium Application Resource agent reconnecting...");
                app = client.applicationGet(node.getApplicationUID());
            }

            // Print to the computer log about the LabelDefinition was chosen
            Resource res = client.applicationResourceGet(app.getUID());
            listener.getLogger().println("Aquarium LabelDefinition: " + label.getDefinitions().get(res.getDefinitionIndex()));
            // Tell computer to know where it runs
            comp.setDefinitionInfo(JSONObject.fromObject(label.getDefinitions().get(res.getDefinitionIndex())));

            // Wait for agent connection for 10 minutes
            int wait_agent_connect = cloud.getAgentConnectWaitMin() * 60 / 5; // 120 * 5 - status_call_time >= 10 mins
            for( int waited_for_agent = 0; waited_for_agent < wait_agent_connect; waited_for_agent++ ) {
                slaveComputer = node.getComputer();
                if( slaveComputer == null ) {
                    throw new IllegalStateException("Node was deleted, computer is null");
                }
                if( slaveComputer.isOnline() ) {
                    break;
                }

                // Check that the resource hasn't failed already
                try {
                    state = client.applicationStateGet(node.getApplicationUID());
                    if( state.getStatus() != ApplicationStatus.ALLOCATED ) {
                        LOG.log(Level.WARNING, "Agent did not connected:" + state.getDescription() + ", node:" + comp.getName());
                        break;
                    }
                } catch( ApiException e ) {
                    LOG.log(Level.WARNING, "Error happened during API request:" + e + ", node:" + comp.getName());
                }

                Thread.sleep(5000);
            }

            slaveComputer = node.getComputer();
            if( slaveComputer == null ) {
                throw new IllegalStateException("Node was deleted, computer is null");
            }
            if( slaveComputer.isOffline() ) {
                if( node != null ) {
                    // Clean up
                    node.terminate();
                }
                throw new IllegalStateException("Agent is not connected, status:" + state);
            }

            // Set up the retention strategy to destroy the node when it's completed processes, idle will initiate the
            // agent termination if no workload was assigned to it.
            node.setRetentionStrategy(new OnceRetentionStrategy(5));

            // Adding listener to the channel to catch any kind of interruptions
            if( computer.getChannel() != null ) {
                computer.getChannel().addListener(new AquariumChannelListener(comp));
            } else {
                LOG.log(Level.WARNING, "Unable to set channel listener since channel is null");
            }
            // Print data again because was cleaned when agent connected
            listener.getLogger().println("Aquarium Application: " + app.getUID() + " with Label: " + label.getName() + ":" + label.getVersion());
            listener.getLogger().println("Aquarium LabelDefinition: " + label.getDefinitions().get(res.getDefinitionIndex()));
            computer.setAcceptingTasks(true);

            this.launched = true;

            try {
                node.save(); // We need to persist the "launched" setting...
            } catch( IOException e ) {
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
