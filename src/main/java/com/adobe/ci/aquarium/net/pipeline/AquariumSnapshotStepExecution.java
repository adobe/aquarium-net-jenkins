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

package com.adobe.ci.aquarium.net.pipeline;

import com.adobe.ci.aquarium.net.AquariumCloud;
import hudson.model.TaskListener;
import hudson.util.LogTaskListener;
import hudson.model.Node;
import hudson.AbortException;
import com.adobe.ci.aquarium.net.AquariumSlave;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;

import java.io.PrintStream;
import java.util.logging.Logger;
import java.util.logging.Level;

public class AquariumSnapshotStepExecution extends SynchronousNonBlockingStepExecution<String> {
    private static final long serialVersionUID = 1L;
    private static final transient Logger LOGGER = Logger.getLogger(AquariumSnapshotStepExecution.class.getName());

    private final AquariumSnapshotStep step;

    AquariumSnapshotStepExecution(AquariumSnapshotStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    private PrintStream logger() {
        TaskListener l = null;
        StepContext context = getContext();
        try {
            l = context.get(TaskListener.class);
        } catch (Exception x) {
            LOGGER.log(Level.WARNING, "Failed to find TaskListener in context");
        } finally {
            if (l == null) {
                l = new LogTaskListener(LOGGER, Level.FINE);
            }
        }
        return l.getLogger();
    }

    @Override
    protected String run() throws Exception {
        boolean full = step.isFull();

        try {
            LOGGER.log(Level.FINE, "Starting containerLog step.");

            Node node = getContext().get(Node.class);
            if( !(node instanceof AquariumSlave) ) {
                throw new AbortException(
                        String.format("Node is not an Aquarium node: %s", node != null ? node.getNodeName() : null));
            }

            Long app_id = ((AquariumSlave)node).getApplicationId();
            AquariumCloud cloud = ((AquariumSlave)node).getAquariumCloud();

            cloud.getClient().applicationSnapshot(app_id, full);

            return "";
        } catch (InterruptedException e) {
            String msg = "Interrupted while requesting snapshot of the Application";
            logger().println(msg);
            LOGGER.log(Level.FINE, msg);
            return "";
        } catch (Exception e) {
            String msg = "Failed to request snapshot of the Application";
            logger().println(msg);
            LOGGER.log(Level.WARNING, msg, e);
            return "";
        }
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        LOGGER.log(Level.FINE, "Stopping Aquarium snapshot step.");
        super.stop(cause);
    }
}
