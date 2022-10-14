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
import com.adobe.ci.aquarium.fish.client.model.Application;
import com.adobe.ci.aquarium.fish.client.model.ApplicationState;
import com.google.common.base.Throwables;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
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
                    node.getLabelString().split(" ")[0], // Using the first label in the list
                    cloud.getJenkinsUrl(),
                    node.getNodeName(),
                    comp.getJnlpMac(),
                    cloud.getMetadata()
            );

            node.setApplicationId(app.getID());

            // Wait for fish node election process - it could take a while if there not enough resources in the pool
            SlaveComputer slaveComputer;
            ApplicationState state = null;
            while( true ) {
                slaveComputer = node.getComputer();
                if( slaveComputer == null ) {
                    throw new IllegalStateException("Node was deleted, computer is null");
                }
                if( slaveComputer.isOnline() ) {
                    break;
                }

                // Check that the resource hasn't failed already
                try {
                    state = client.applicationStateGet(app.getID());
                    if( state.getStatus() == ApplicationState.StatusEnum.ALLOCATED ) {
                        break;
                    } else if( state.getStatus() != ApplicationState.StatusEnum.ELECTED && state.getStatus() != ApplicationState.StatusEnum.NEW) {
                        // Resource launch failed
                        LOG.log(Level.WARNING, "Unable to get resource from pool:" + state.getDescription() + ", node:" + comp.getName());
                        break;
                    }
                } catch( ApiException e ) {
                    LOG.log(Level.WARNING, "Error happened during API request:" + e.toString() + ", node:" + comp.getName());
                }

                Thread.sleep(5000);
            }

            // Wait for agent connection for 10 minutes
            int wait_agent_connect = 120; // 120 * 5 - status_call_time >= 10 mins
            for(int waited_for_agent = 0; waited_for_agent < wait_agent_connect; waited_for_agent++ ) {
                slaveComputer = node.getComputer();
                if( slaveComputer == null ) {
                    throw new IllegalStateException("Node was deleted, computer is null");
                }
                if( slaveComputer.isOnline() ) {
                    break;
                }

                // Check that the resource hasn't failed already
                try {
                    state = client.applicationStateGet(app.getID());
                    if( state.getStatus() != ApplicationState.StatusEnum.ALLOCATED ) {
                        LOG.log(Level.WARNING, "Agent did not connected:" + state.getDescription() + ", node:" + comp.getName());
                        break;
                    }
                } catch( ApiException e ) {
                    LOG.log(Level.WARNING, "Error happened during API request:" + e.toString() + ", node:" + comp.getName());
                }

                Thread.sleep(5000);
            }

            if( slaveComputer.isOffline() ) {
                if( node != null ) {
                    // Clean up
                    node.terminate();
                }
                throw new IllegalStateException("Agent is not connected, status:" + state.toString());
            }

            // Set up the retention strategy to destroy the node when it's completed processes
            node.setRetentionStrategy(new OnceRetentionStrategy(5));
            computer.setAcceptingTasks(true);
            launched = true;

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
