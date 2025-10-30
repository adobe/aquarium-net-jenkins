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

/**
 * Model class for Aquarium Fish ApplicationState.
 * Wraps the protobuf generated ApplicationState message for easier use in Jenkins plugin.
 */
public class ApplicationState {
    private final ApplicationOuterClass.ApplicationState protoApplicationState;

    public ApplicationState(ApplicationOuterClass.ApplicationState protoApplicationState) {
        this.protoApplicationState = protoApplicationState;
    }

    public String getUid() {
        return protoApplicationState.getUid();
    }

    public Instant getCreatedAt() {
        Timestamp timestamp = protoApplicationState.getCreatedAt();
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    public String getApplicationUid() {
        return protoApplicationState.getApplicationUid();
    }

    public ApplicationOuterClass.ApplicationState.Status getStatus() {
        return protoApplicationState.getStatus();
    }

    public String getDescription() {
        return protoApplicationState.getDescription();
    }

    public ApplicationOuterClass.ApplicationState getProtoApplicationState() {
        return protoApplicationState;
    }

    @Override
    public String toString() {
        return "ApplicationState{" +
                "uid='" + getUid() + '\'' +
                ", applicationUid='" + getApplicationUid() + '\'' +
                ", status=" + getStatus() +
                ", description='" + getDescription() + '\'' +
                ", createdAt=" + getCreatedAt() +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApplicationState that = (ApplicationState) o;
        return getUid().equals(that.getUid());
    }

    @Override
    public int hashCode() {
        return getUid().hashCode();
    }
}
