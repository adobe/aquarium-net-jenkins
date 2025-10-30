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
import com.adobe.ci.aquarium.net.util.ProtobufJsonUtil;
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
        String yamlString = ProtobufJsonUtil.toYaml(this.getProtoLabel());
        return yamlString;
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
