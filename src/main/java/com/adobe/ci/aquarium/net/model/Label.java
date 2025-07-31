/**
 * Copyright 2025 Adobe. All rights reserved.
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

package com.adobe.ci.aquarium.net.model;

import aquarium.v2.LabelOuterClass;
import com.google.protobuf.Timestamp;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Model class for Aquarium Fish Label.
 * Wraps the protobuf generated Label message for easier use in Jenkins plugin.
 */
public class Label {
    private final LabelOuterClass.Label protoLabel;

    public Label(LabelOuterClass.Label protoLabel) {
        this.protoLabel = protoLabel;
    }

    public String getUid() {
        return protoLabel.getUid();
    }

    public String getName() {
        return protoLabel.getName();
    }

    public int getVersion() {
        return protoLabel.getVersion();
    }

    public Instant getCreatedAt() {
        Timestamp timestamp = protoLabel.getCreatedAt();
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    public List<LabelOuterClass.LabelDefinition> getDefinitions() {
        return protoLabel.getDefinitionsList();
    }

    public Map<String, String> getMetadata() {
        Map<String, String> metadata = new HashMap<>();
        protoLabel.getMetadata().getFieldsMap().forEach((key, value) -> {
            if (value.hasStringValue()) {
                metadata.put(key, value.getStringValue());
            } else {
                metadata.put(key, value.toString());
            }
        });
        return metadata;
    }

    public LabelOuterClass.Label getProtoLabel() {
        return protoLabel;
    }

    /**
     * Get a human-readable description of this label in YAML format
     */
    public String getYamlDescription() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: ").append(getName()).append("\n");
        yaml.append("version: ").append(getVersion()).append("\n");
        yaml.append("uid: ").append(getUid()).append("\n");
        yaml.append("created_at: ").append(getCreatedAt()).append("\n");
        yaml.append("definitions:\n");

        for (int i = 0; i < getDefinitions().size(); i++) {
            LabelOuterClass.LabelDefinition def = getDefinitions().get(i);
            yaml.append("  - driver: ").append(def.getDriver()).append("\n");
            yaml.append("    resources:\n");
            yaml.append("      cpu: ").append(def.getResources().getCpu()).append("\n");
            yaml.append("      ram: ").append(def.getResources().getRam()).append("\n");
            if (!def.getResources().getNetwork().isEmpty()) {
                yaml.append("      network: ").append(def.getResources().getNetwork()).append("\n");
            }
            if (def.getResources().getDisksCount() > 0) {
                yaml.append("      disks:\n");
                def.getResources().getDisksMap().forEach((key, disk) -> {
                    yaml.append("        ").append(key).append(":\n");
                    yaml.append("          size: ").append(disk.getSize()).append("\n");
                    if (disk.getReuse()) {
                        yaml.append("          reuse: true\n");
                    }
                });
            }
            if (def.getOptions().getFieldsCount() > 0) {
                yaml.append("    options:\n");
                def.getOptions().getFieldsMap().forEach((key, value) -> {
                    yaml.append("      ").append(key).append(": ").append(value).append("\n");
                });
            }
        }

        if (getMetadata().size() > 0) {
            yaml.append("metadata:\n");
            getMetadata().forEach((key, value) -> {
                yaml.append("  ").append(key).append(": ").append(value).append("\n");
            });
        }

        return yaml.toString();
    }

    @Override
    public String toString() {
        return "Label{" +
                "name='" + getName() + '\'' +
                ", version=" + getVersion() +
                ", uid='" + getUid() + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Label label = (Label) o;
        return getUid().equals(label.getUid());
    }

    @Override
    public int hashCode() {
        return getUid().hashCode();
    }
}
