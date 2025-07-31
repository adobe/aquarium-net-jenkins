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

package com.adobe.ci.aquarium.net.pipeline;

import aquarium.v2.ApplicationOuterClass.ApplicationState.Status;
import com.adobe.ci.aquarium.net.AquariumCloud;
import hudson.model.TaskListener;
import hudson.util.LogTaskListener;
import hudson.model.Node;
import hudson.AbortException;
import com.adobe.ci.aquarium.net.AquariumSlave;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;

import java.io.PrintStream;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.logging.Level;

public class AquariumCreateImageStepExecution extends SynchronousNonBlockingStepExecution<String> {
    private static final long serialVersionUID = 1L;
    private static final transient Logger LOGGER = Logger.getLogger(AquariumCreateImageStepExecution.class.getName());

    private final AquariumCreateImageStep step;

    AquariumCreateImageStepExecution(AquariumCreateImageStep step, StepContext context) {
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
        Status when = Status.valueOf(step.getWhen());

        try {
            LOGGER.log(Level.FINE, "Starting Aquarium Create Image step.");

            Node node = getContext().get(Node.class);
            if( !(node instanceof AquariumSlave) ) {
                throw new AbortException(
                        String.format("Node is not an Aquarium node: %s", node != null ? node.getNodeName() : null));
            }

            UUID app_id = ((AquariumSlave)node).getApplicationUID();
            AquariumCloud cloud = ((AquariumSlave)node).getAquariumCloud();

            String taskUidString = cloud.getClient().applicationTaskImage(app_id, when, full);

            return taskUidString;
        } catch (InterruptedException e) {
            String msg = "Interrupted while requesting create image of the Application";
            logger().println(msg);
            LOGGER.log(Level.FINE, msg);
            return "";
        } catch (Exception e) {
            String msg = "Failed to request create image of the Application";
            logger().println(msg);
            LOGGER.log(Level.WARNING, msg, e);
            return "";
        }
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        LOGGER.log(Level.FINE, "Stopping Aquarium Create Image step.");
        super.stop(cause);
    }
}
