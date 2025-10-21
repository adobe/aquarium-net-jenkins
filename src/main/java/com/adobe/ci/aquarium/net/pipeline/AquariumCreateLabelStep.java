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

import com.adobe.ci.aquarium.net.AquariumCloud;
import com.adobe.ci.aquarium.net.config.TemplateVariable;
import hudson.Extension;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import javax.annotation.Nonnull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import jenkins.model.Jenkins;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

public class AquariumCreateLabelStep extends Step implements Serializable {
    private static final long serialVersionUID = 1L;

    private String templateId;
    private List<TemplateVariable> variables;

    @DataBoundConstructor
    public AquariumCreateLabelStep(String templateId) {
        this.templateId = templateId;
        this.variables = new ArrayList<>();
    }

    @DataBoundSetter
    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    @DataBoundSetter
    public void setVariables(List<TemplateVariable> variables) {
        this.variables = variables != null ? new ArrayList<>(variables) : new ArrayList<>();
    }

    public String getTemplateId() {
        return templateId;
    }

    public List<TemplateVariable> getVariables() {
        return new ArrayList<>(variables);
    }

    public Map<String, String> getVariablesAsMap() {
        Map<String, String> result = new HashMap<>();
        if (variables != null) {
            for (TemplateVariable var : variables) {
                if (var.getKey() != null && var.getValue() != null) {
                    result.put(var.getKey(), var.getValue());
                }
            }
        }
        return result;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new AquariumCreateLabelStepExecution(this, context);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "aquariumCreateLabel";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Create Aquarium Label from Template";
        }

        @Override
        public boolean isAdvanced() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.unmodifiableSet(new HashSet<>());
        }

        // Used to fill the pipeline step snippet generator `templateId` field
        public ListBoxModel doFillTemplateIdItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Select template...", "");

            // Get all available templates from all Aquarium clouds
            for (hudson.slaves.Cloud cloud : Jenkins.get().clouds) {
                if (cloud instanceof AquariumCloud) {
                    AquariumCloud aquariumCloud = (AquariumCloud) cloud;
                    List<com.adobe.ci.aquarium.net.config.AquariumLabelTemplate> templates = aquariumCloud.getLabelTemplates();
                    if (templates != null) {
                        for (com.adobe.ci.aquarium.net.config.AquariumLabelTemplate template : templates) {
                            items.add(template.getName() + " (" + aquariumCloud.getName() + ")", template.getId());
                        }
                    }
                }
            }

            return items;
        }
    }
}
