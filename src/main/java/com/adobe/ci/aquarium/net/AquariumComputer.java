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

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.queue.SubTask;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.slaves.AbstractCloudComputer;
import net.sf.json.JSONObject;
import org.acegisecurity.Authentication;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution.PlaceholderTask;
import org.jetbrains.annotations.NotNull;

import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AquariumComputer extends AbstractCloudComputer<AquariumSlave> {
    private static final Logger LOG = Logger.getLogger(AquariumComputer.class.getName());

    private boolean launching;

    private JSONObject appInfo;
    private JSONObject definitionInfo;

    public AquariumComputer(AquariumSlave slave) {
        super(slave);
    }

    public JSONObject getAppInfo() {
        return this.appInfo;
    }
    public JSONObject getDefinitionInfo() {
        return this.definitionInfo;
    }

    public void setAppInfo(JSONObject info) {
        this.appInfo = info;
    }

    public void setDefinitionInfo(JSONObject info) {
        this.definitionInfo = info;
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);
        Queue.Executable exec = executor.getCurrentExecutable();
        LOG.log(Level.INFO, " Computer {0} accepted task {1}", new Object[] {this, exec});

        // Tell the current workflow about the node we're executing on
        // Not that great solution - will be better to use the node step listener somehow, but I did not found a way to do that
        try {
            SubTask parent = exec.getParent();
            if( parent instanceof PlaceholderTask ) {
                PlaceholderTask wf_run = (PlaceholderTask) parent;
                PrintStream logger = wf_run.getNode().getExecution().getOwner().getListener().getLogger();
                if( !this.appInfo.isEmpty() ) {
                    logger.println("Aquarium Application: " + this.appInfo);
                }
                if( !this.definitionInfo.isEmpty() ) {
                    logger.println("Aquarium Definition: " + this.definitionInfo);
                }
            } else {
                LOG.log(Level.WARNING, "Incorrect definition or executor to notify: Aquarium LabelDefinition: " + this.definitionInfo);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Unable to notify task about node resource: Aquarium LabelDefinition: " + this.definitionInfo);
        }
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        Queue.Executable exec = executor.getCurrentExecutable();
        LOG.log(Level.FINE, " Computer {0} completed task {1}", new Object[] {this, exec});

        setAcceptingTasks(false);
        super.taskCompleted(executor, task, durationMS);
        done();
    }

    private void done() {
        // Terminate the node
        try {
            AquariumSlave node = getNode();
            if( node == null ) {
                LOG.log(Level.WARNING, "Unable to terminate null node: " + getNode());
                setAcceptingTasks(true);
                return;
            }
            node.terminate();
        } catch( Exception ex ) {
            LOG.log(Level.WARNING, "Unable to terminate node due to exception: " + getNode(), ex);
        }
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        setAcceptingTasks(false);
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        Queue.Executable exec = executor.getCurrentExecutable();
        LOG.log(Level.INFO, " Computer {0} completed task {1} with problems", new Object[] {this, exec});
        done();
    }

    @Override
    public String toString() {
        return String.format("AquariumComputer name: %s slave: %s", getName(), getNode());
    }

    @NotNull
    @Override
    public ACL getACL() {
        final ACL base = super.getACL();
        return new ACL() {
            @Override
            public boolean hasPermission(@NotNull Authentication a, @NotNull Permission permission) {
                return permission == Computer.CONFIGURE ? false : base.hasPermission(a,permission);
            }
        };
    }

    public void setLaunching(boolean launching) {
        this.launching = launching;
    }

    public boolean isLaunching() {
        return launching;
    }

    @Override
    public void setAcceptingTasks(boolean acceptingTasks) {
        super.setAcceptingTasks(acceptingTasks);
        if (acceptingTasks) {
            launching = false;
        }
    }
}
