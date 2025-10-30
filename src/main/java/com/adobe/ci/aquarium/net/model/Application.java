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

import aquarium.v2.ApplicationOuterClass;
import com.google.protobuf.Timestamp;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Model class for Aquarium Fish Application.
 * Wraps the protobuf generated Application message for easier use in Jenkins plugin.
 */
public class Application {
    private final ApplicationOuterClass.Application protoApplication;

    public Application(ApplicationOuterClass.Application protoApplication) {
        this.protoApplication = protoApplication;
    }

    public String getUid() {
        return protoApplication.getUid();
    }

    public Instant getCreatedAt() {
        Timestamp timestamp = protoApplication.getCreatedAt();
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    public String getOwnerName() {
        return protoApplication.getOwnerName();
    }

    public String getLabelUid() {
        return protoApplication.getLabelUid();
    }

    public Map<String, String> getMetadata() {
        Map<String, String> metadata = new HashMap<>();
        protoApplication.getMetadata().getFieldsMap().forEach((key, value) -> {
            if (value.hasStringValue()) {
                metadata.put(key, value.getStringValue());
            } else {
                metadata.put(key, value.toString());
            }
        });
        return metadata;
    }

    public ApplicationOuterClass.Application getProtoApplication() {
        return protoApplication;
    }

    @Override
    public String toString() {
        return "Application{" +
                "uid='" + getUid() + '\'' +
                ", ownerName='" + getOwnerName() + '\'' +
                ", labelUid='" + getLabelUid() + '\'' +
                ", createdAt=" + getCreatedAt() +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Application that = (Application) o;
        return getUid().equals(that.getUid());
    }

    @Override
    public int hashCode() {
        return getUid().hashCode();
    }
}
