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

package com.adobe.ci.aquarium.net.config;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Configuration for Aquarium label templates used in pipeline steps.
 * Templates allow reusable label definitions with variable substitution.
 */
public class AquariumLabelTemplate extends AbstractDescribableImpl<AquariumLabelTemplate> implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String description;
    private String templateContent;

    @DataBoundConstructor
    public AquariumLabelTemplate(String id, String name, String templateContent) {
        this.id = id;
        this.name = name;
        this.templateContent = templateContent;
    }

    public String getId() {
        return id;
    }

    @DataBoundSetter
    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    @DataBoundSetter
    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    @DataBoundSetter
    public void setDescription(String description) {
        this.description = description;
    }

    public String getTemplateContent() {
        return templateContent;
    }

    @DataBoundSetter
    public void setTemplateContent(String templateContent) {
        this.templateContent = templateContent;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AquariumLabelTemplate> {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Aquarium Label Template";
        }
    }
}
