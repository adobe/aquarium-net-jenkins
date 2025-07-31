/**
 * Copyright 2021-2025 Adobe. All rights reserved.
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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class LabelMapping extends AbstractDescribableImpl<LabelMapping> implements Serializable {
    private String pattern;
    private String labels;

    @DataBoundConstructor
    public LabelMapping(String pattern, String labels) {
        this.pattern = pattern;
        this.labels = labels;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getLabels() {
        return labels;
    }

    public void setLabels(String labels) {
        this.labels = labels;
    }

    /**
     * Finds all the matching labels for specified label
     */
    @NonNull
    static String getLabels(@NonNull Iterable<LabelMapping> labels, String label) {
        List<String> found_labels = new ArrayList<>();
        for (LabelMapping labelMapping : labels) {
            if( Pattern.compile(labelMapping.getPattern()).matcher(label).matches() ) {
                found_labels.add(labelMapping.getLabels());
            }
        }
        return String.join(" ", found_labels);
    }

    @Extension
    @Symbol("labelMapping")
    public static class DescriptorImpl extends Descriptor<LabelMapping> {
        @Override
        @NonNull
        public String getDisplayName() {
            return "Label Mapping";
        }
    }
}
