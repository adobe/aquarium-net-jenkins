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

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Simple key-value pair for template variables
 */
public class TemplateVariable extends AbstractDescribableImpl<TemplateVariable> implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String key;
    private final String value;

    @DataBoundConstructor
    public TemplateVariable(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<TemplateVariable> {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Template Variable";
        }
    }
}
