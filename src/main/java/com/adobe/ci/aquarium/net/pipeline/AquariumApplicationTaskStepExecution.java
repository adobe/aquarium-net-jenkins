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

import aquarium.v2.ApplicationOuterClass.ApplicationTask;
import com.adobe.ci.aquarium.net.AquariumCloud;
import com.adobe.ci.aquarium.net.util.ProtobufJsonUtil;
import hudson.model.TaskListener;
import hudson.util.LogTaskListener;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;

import java.io.PrintStream;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.logging.Level;

public class AquariumApplicationTaskStepExecution extends SynchronousNonBlockingStepExecution<Object> {
    private static final long serialVersionUID = 1L;
    private static final transient Logger LOGGER = Logger.getLogger(AquariumCreateImageStepExecution.class.getName());

    private final AquariumApplicationTaskStep step;

    AquariumApplicationTaskStepExecution(AquariumApplicationTaskStep step, StepContext context) {
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
    protected Object run() throws Exception {
        boolean wait = step.getWait();
        UUID task_uid = UUID.fromString(step.getTaskUid());
        JSONObject out = new JSONObject();

        try {
            LOGGER.log(Level.FINE, "Starting Aquarium ApplicationTask step.");

            // Since it makes sense to execute the step outside the Aquarium worker, we get through the available clouds
            // to find the right one where the task exists. That should not cause any issues because jenkins is usually
            // connected to just one Aquarium cluster at a time.
            List<AquariumCloud> clouds = Jenkins.get().clouds.getAll(AquariumCloud.class);
            for( AquariumCloud cloud : clouds ) {
                try {
                    while( true ) {
                        ApplicationTask task = cloud.getClient().taskGet(task_uid);
                        // Convert protobuf to JSONObject for output
                        if( !task.getResult().toString().isEmpty() || !wait) {
                            // Yes, in theory we need to use protobuf utils to convert to JSON, but too much hassle with deps
                            out = ProtobufJsonUtil.toJson(task);
                            return out;
                        }
                        Thread.sleep(10000);
                    }
                } catch (InterruptedException ie) {
                    String msg = "Interrupted while requesting ApplicationTask";
                    logger().println(msg);
                    LOGGER.log(Level.FINE, msg);
                    Thread.currentThread().interrupt();
                    return out;
                } catch (Exception e) {
                    String msg = "ApplicationTask was not found on AquariumCluster " + cloud.getName();
                    logger().println(msg);
                    LOGGER.log(Level.INFO, msg, e);
                }
            }
        } catch (Exception e) {
            String msg = "Failed to request ApplicationTask";
            logger().println(msg);
            LOGGER.log(Level.WARNING, msg, e);
        }
        return out;
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        LOGGER.log(Level.FINE, "Stopping Aquarium ApplicationTask step.");
        super.stop(cause);
    }


}
