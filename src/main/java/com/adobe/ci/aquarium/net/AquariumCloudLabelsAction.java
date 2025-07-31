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

package com.adobe.ci.aquarium.net;

import com.adobe.ci.aquarium.net.model.Label;
import hudson.Extension;
import hudson.model.ManagementLink;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Management link to display available Aquarium Fish labels
 */
@Extension
public class AquariumCloudLabelsAction extends ManagementLink {

    @Override
    public String getIconFileName() {
        return "symbol-fish";
    }

    @Override
    public String getDisplayName() {
        return "Aquarium Fish Labels";
    }

    @Override
    public String getUrlName() {
        return "aquarium-labels";
    }

    @Override
    public String getDescription() {
        return "View all available Fish labels from Aquarium clusters";
    }

    /**
     * Get all Fish labels from all configured Aquarium clouds
     */
    public List<LabelInfo> getAllLabels() {
        List<LabelInfo> allLabels = new ArrayList<>();

        for (hudson.slaves.Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof AquariumCloud) {
                AquariumCloud aquariumCloud = (AquariumCloud) cloud;

                // Get labels from the cloud's cache
                Map<String, Label> fishLabels = aquariumCloud.getFishLabelsCache();
                for (Label fishLabel : fishLabels.values()) {
                    LabelInfo labelInfo = new LabelInfo(
                        fishLabel.getName(),
                        fishLabel.getVersion(),
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(fishLabel.getCreatedAt()),
                        fishLabel.getYamlDescription(),
                        aquariumCloud.getName(),
                        aquariumCloud.isConnected()
                    );
                    allLabels.add(labelInfo);
                }
            }
        }

        // Sort by cloud name, then by label name, then by version (descending)
        allLabels.sort((a, b) -> {
            int cloudCompare = a.cloudName.compareTo(b.cloudName);
            if (cloudCompare != 0) return cloudCompare;

            int nameCompare = a.name.compareTo(b.name);
            if (nameCompare != 0) return nameCompare;

            return Integer.compare(b.version, a.version); // Descending version
        });

        return allLabels;
    }

    /**
     * Get summary statistics
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new HashMap<>();
        List<LabelInfo> allLabels = getAllLabels();

        summary.put("totalLabels", allLabels.size());

        Set<String> uniqueLabels = new HashSet<>();
        Set<String> connectedClouds = new HashSet<>();
        Set<String> disconnectedClouds = new HashSet<>();

        for (LabelInfo label : allLabels) {
            uniqueLabels.add(label.name);
            if (label.cloudConnected) {
                connectedClouds.add(label.cloudName);
            } else {
                disconnectedClouds.add(label.cloudName);
            }
        }

        summary.put("uniqueLabels", uniqueLabels.size());
        summary.put("connectedClouds", connectedClouds.size());
        summary.put("disconnectedClouds", disconnectedClouds.size());

        return summary;
    }

    /**
     * Data class for label information
     */
    public static class LabelInfo {
        public final String name;
        public final int version;
        public final String createdAt;
        public final String yamlDescription;
        public final String cloudName;
        public final boolean cloudConnected;

        public LabelInfo(String name, int version, String createdAt, String yamlDescription,
                        String cloudName, boolean cloudConnected) {
            this.name = name;
            this.version = version;
            this.createdAt = createdAt;
            this.yamlDescription = yamlDescription;
            this.cloudName = cloudName;
            this.cloudConnected = cloudConnected;
        }

        public String getName() { return name; }
        public int getVersion() { return version; }
        public String getCreatedAt() { return createdAt; }
        public String getYamlDescription() { return yamlDescription; }
        public String getCloudName() { return cloudName; }
        public boolean isCloudConnected() { return cloudConnected; }
        public String getFullName() { return name + ":" + version; }
    }
}
