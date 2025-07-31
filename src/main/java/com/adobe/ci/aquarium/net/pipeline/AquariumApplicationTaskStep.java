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

import hudson.Extension;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import javax.annotation.Nonnull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.Serializable;
import java.util.*;

public class AquariumApplicationTaskStep extends Step implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID taskUid;
    private Boolean wait;

    @DataBoundConstructor public AquariumApplicationTaskStep(String taskUid, boolean wait) {
        this.taskUid = taskUid != null ? UUID.fromString(taskUid) : new UUID(0,0);
        this.wait = wait;
    }

    @DataBoundSetter
    public void setTaskUid(String taskUid) {
        this.taskUid = taskUid != null ? UUID.fromString(taskUid) : new UUID(0,0);
    }

    @DataBoundSetter
    public void setWait(boolean wait) {
        this.wait = wait;
    }

    public String getTaskUid() {
        return taskUid.toString();
    }

    public boolean getWait() {
        if( this.wait == null ) {
            return false;
        }
        return this.wait;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new AquariumApplicationTaskStepExecution(this, context);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "aquariumApplicationTask";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Get the ApplicationTask data by it's UID and wait if needed";
        }

        @Override
        public boolean isAdvanced() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.unmodifiableSet(new HashSet<>());
        }
    }
}
