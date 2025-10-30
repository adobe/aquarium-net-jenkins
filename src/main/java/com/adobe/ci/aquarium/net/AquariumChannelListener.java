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
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.nio.charset.StandardCharsets;

import aquarium.v2.ApplicationOuterClass.ApplicationState.Status;
import com.adobe.ci.aquarium.net.model.ApplicationState;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.remoting.Channel;
import hudson.model.TaskListener;
import java.io.PrintStream;
import java.lang.reflect.Method;
import org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import hudson.model.Job;
import hudson.model.Run;
import hudson.slaves.OfflineCause;

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
        // If this computer was intentionally disconnected by our plugin during graceful termination,
        // skip any abort logic. This happens when a node block completed and we deallocate the resource.
        OfflineCause offlineCause = computer.getOfflineCause();
        boolean intentional = (offlineCause instanceof AquariumSlave.AquariumOfflineCause);
        if (!intentional) {
            intentional = computer.wasIntentionalDisconnect();
        }
        if (intentional) {
            LOG.log(Level.FINE, "Channel closed due to intentional Aquarium deallocation on computer: " + computer);
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

        // Proceed to abort only when the application is not in a healthy allocated state
        if (state.getStatus() != Status.ALLOCATED) {
            // Abort only if there are still running executors. If the node finished its work and was intentionally
            // deallocated at the end of a stage, there may be no active executors and the pipeline should continue.
            boolean hasBusyExecutors = false;
            for (Executor exec : computer.getAllExecutors()) {
                if (exec.getCurrentExecutable() != null) {
                    LOG.fine("Current executable is busy: " + exec.getCurrentExecutable() + " on computer:" + computer);
                    hasBusyExecutors = true;
                    break;
                }
            }

            if (hasBusyExecutors) {
                String interruptionMsg = new AquariumCauseOfInterruption(state.getStatus(), state.getDescription()).getShortDescription();
                for (Executor exec : computer.getAllExecutors()) {
                    // Best-effort: append to Pipeline build log if we can resolve the run from the executable
                    try {
                        // Unfortunately this is not working for some reason and not printing to the log of the integration test
                        /*Method getListenerMethod = exec.getClass().getMethod("getListener");
                        Object listenerObj = getListenerMethod.invoke(exec);
                        if (listenerObj instanceof TaskListener) {
                            PrintStream logger = ((TaskListener) listenerObj).getLogger();
                            logger.println(interruptionMsg);
                        }*/
                        Object currentExecutable = exec.getCurrentExecutable();
                        if (currentExecutable != null) {
                            String execStr = String.valueOf(currentExecutable);
                            int runIdIdx = execStr.indexOf("runId=");
                            if (runIdIdx >= 0) {
                                int start = runIdIdx + 6;
                                int end = execStr.indexOf(',', start);
                                if (end < 0) end = execStr.indexOf('}', start);
                                if (end > start) {
                                    String runId = execStr.substring(start, end);
                                    int hash = runId.lastIndexOf('#');
                                    if (hash > 0 && hash < runId.length() - 1) {
                                        String jobFullName = runId.substring(0, hash);
                                        String buildNumStr = runId.substring(hash + 1);
                                        try {
                                            int buildNum = Integer.parseInt(buildNumStr);
                                            Job<?,?> job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
                                            if (job != null) {
                                                Run<?,?> run = job.getBuildByNumber(buildNum);
                                                if (run != null && run.getRootDir() != null) {
                                                    java.io.File f = new java.io.File(run.getRootDir(), "log");
                                                    try (StreamTaskListener stl = new StreamTaskListener(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                                                        stl.getLogger().println(interruptionMsg);
                                                    }
                                                }
                                            }
                                        } catch (NumberFormatException nfe) {
                                            // ignore
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        LOG.log(Level.FINE, "Unable to append Aquarium interruption message to Pipeline run log from executable: " + exec.getCurrentExecutable(), t);
                    }

                    // Best-effort: print to the executor TaskListener (Pipeline console) if available
                    try {
                        Method getListenerMethod = exec.getClass().getMethod("getListener");
                        Object listenerObj = getListenerMethod.invoke(exec);
                        if (listenerObj instanceof TaskListener) {
                            PrintStream logger = ((TaskListener) listenerObj).getLogger();
                            logger.println(interruptionMsg);
                        }
                    } catch (Throwable t) {
                        LOG.log(Level.FINE, "Unable to print interruption message to executor listener: " + exec, t);
                    }

                    // Using ExceededTimeout here to keep the existing cause timeout detections work as expected
                    LOG.info("Interrupting executor " + exec + " with Result.ABORTED, AquariumCauseOfInterruption and ExceededTimeout");
                    // Put AquariumCauseOfInterruption first so its short description is printed where supported
                    exec.interrupt(Result.ABORTED, new AquariumCauseOfInterruption(state.getStatus(), state.getDescription()), new ExceededTimeout());
                }
            } else {
                LOG.log(Level.FINE, "No active executors on {0} at channel close; skipping abort.", computer);
            }
        }
    }
}
