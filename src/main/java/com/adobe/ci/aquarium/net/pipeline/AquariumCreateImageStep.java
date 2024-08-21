/**
 * Copyright 2024 Adobe. All rights reserved.
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

import com.adobe.ci.aquarium.fish.client.model.ApplicationStatus;
import hudson.Extension;
import hudson.model.Node;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class AquariumCreateImageStep extends Step implements Serializable {
    private static final long serialVersionUID = 1L;

    private Boolean full;
    private String when;

    @DataBoundConstructor public AquariumCreateImageStep(Boolean full, String when) {
        this.full = full;
        this.when = when;
    }

    @DataBoundSetter
    public void setFull(boolean full) {
        this.full = full;
    }

    @DataBoundSetter
    public void setWhen(String status) {
        if( status.isEmpty() ) {
            // Back to default
            this.when = null;
        } else {
            this.when = ApplicationStatus.fromValue(status).toString();
        }
    }

    public boolean isFull() {
        if( this.full == null ) {
            return false;
        }
        return this.full;
    }

    public String getWhen() {
        if( this.when == null ) {
            return ApplicationStatus.ALLOCATED.toString();
        }
        return this.when;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new AquariumCreateImageStepExecution(this, context);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "aquariumCreateImage";
        }

        @NotNull
        @Override
        public String getDisplayName() {
            return "Make image of the current worker";
        }

        @Override
        public boolean isAdvanced() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(Node.class)));
        }

        // Used to fill the pipeline step snippet generator `when` field
        public ListBoxModel doFillWhenItems() {
            ListBoxModel items = new ListBoxModel();
            items.add(ApplicationStatus.ALLOCATED.name());
            items.add(ApplicationStatus.DEALLOCATE.name());
            return items;
        }
    }
}
