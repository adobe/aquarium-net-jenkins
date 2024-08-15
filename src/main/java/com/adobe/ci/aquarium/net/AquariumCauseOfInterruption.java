package com.adobe.ci.aquarium.net;

import com.adobe.ci.aquarium.fish.client.model.ApplicationStatus;
import hudson.model.TaskListener;
import jenkins.model.CauseOfInterruption;

public class AquariumCauseOfInterruption extends CauseOfInterruption {
    private static final long serialVersionUID = 1L;

    private final ApplicationStatus status;
    private final String description;

    public AquariumCauseOfInterruption(ApplicationStatus status, String description) {
        this.status = status;
        this.description = description;
    }

    public ApplicationStatus getStatus() {
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
