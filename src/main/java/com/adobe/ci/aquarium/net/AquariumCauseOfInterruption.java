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

package com.adobe.ci.aquarium.net;

import aquarium.v2.ApplicationOuterClass.ApplicationState.Status;
import hudson.model.TaskListener;
import jenkins.model.CauseOfInterruption;

public class AquariumCauseOfInterruption extends CauseOfInterruption {
    private static final long serialVersionUID = 1L;

    private final Status status;
    private final String description;

    public AquariumCauseOfInterruption(Status status, String description) {
        this.status = status;
        this.description = description;
    }

    public Status getStatus() {
        return status;
    }
    public String getDescription() {
        return description;
    }

    @Override
    public String getShortDescription() {
        return "Interrupted by Aquarium, Application State: " + status.toString() + ": " + description;
    }

    @Override
    public void print(TaskListener listener) {
        listener.getLogger().println(getShortDescription());
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        AquariumCauseOfInterruption that = (AquariumCauseOfInterruption) o;
        return status.equals(that.status) && description.equals(that.description);
    }

    @Override
    public int hashCode() {
        return (status.toString()+description).hashCode();
    }
}
