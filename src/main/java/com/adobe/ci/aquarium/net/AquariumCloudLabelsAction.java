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
import hudson.model.Action;
import jenkins.model.TransientActionFactory;
import org.kohsuke.accmod.Restricted;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Attach available labels to the AquariumCloud status/index page
 */
@Restricted(NoExternalUse.class)
public class AquariumCloudLabelsAction implements Action {
    public final AquariumCloud cloud;

    public AquariumCloudLabelsAction(AquariumCloud cloud) {
        this.cloud = cloud;
    }

    // Empty constructor for InjectedTest tests only
    public AquariumCloudLabelsAction() {
        this.cloud = null;
    }

    // We don't need to display the menu item - just to show summary, so returning null for required methods
    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }

    /**
     * Get all Fish labels from all configured Aquarium clouds
     */
    public List<LabelInfo> getLabelsLatest() {
        cloud.ensureConnected();
        TreeMap<String, Label> latestLabels = new TreeMap<String, Label>();

        // Get labels from the cloud's cache
        List<Label> fishLabels = new ArrayList<Label>(cloud.getFishLabelsCache().values());

        // Filtering to keep only the latest ones
        for( Label label : fishLabels ) {
            Label latestLabel = latestLabels.get(label.getName());
            if( latestLabel == null || latestLabel.getVersion() < label.getVersion() ) {
                latestLabels.put(label.getName(), label);
            }
        }

        // Prepare the JSON array
        List<LabelInfo> out = new ArrayList<LabelInfo>();
        for( Label label : latestLabels.values() ) {
            out.add(new LabelInfo(label.getName(), label.getVersion(), label.getCreatedAt().toString(), label.getYamlDescription()));
        }

        return out;
    }

    @Extension
    public static final class CloudActionFactory extends TransientActionFactory<AquariumCloud> {
        @Override
        public Class<AquariumCloud> type() {
            return AquariumCloud.class;
        }

        @Nonnull
        @Override
        public Collection<? extends Action> createFor(@Nonnull AquariumCloud cloud) {
            return Collections.singletonList(new AquariumCloudLabelsAction(cloud));
        }
    }

    /**
     * Data class for label information
     */
    public static class LabelInfo {
        public final String name;
        public final int version;
        public final String createdAt;
        public final String yamlDescription;

        public LabelInfo(String name, int version, String createdAt, String yamlDescription) {
            this.name = name;
            this.version = version;
            this.createdAt = createdAt;
            this.yamlDescription = yamlDescription;
        }

        public String getName() { return name; }
        public int getVersion() { return version; }
        public String getCreatedAt() { return createdAt; }
        public String getYamlDescription() { return yamlDescription; }
        public String getFullName() { return name + ":" + version; }
     }
}
