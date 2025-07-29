/**
 * Copyright 2021 Adobe. All rights reserved.
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

import com.adobe.ci.aquarium.fish.client.ApiException;
import com.adobe.ci.aquarium.fish.client.model.ApplicationState;
import com.adobe.ci.aquarium.fish.client.model.ApplicationStatus;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.remoting.Engine;
import hudson.remoting.VirtualChannel;
import hudson.slaves.*;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.jetbrains.annotations.NotNull;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AquariumSlave extends AbstractCloudSlave {

    private static final Logger LOG = Logger.getLogger(AquariumSlave.class.getName());

    private static final Integer DISCONNECTION_TIMEOUT = Integer
            .getInteger(AquariumSlave.class.getName() + ".disconnectionTimeout", 5);

    private static final long serialVersionUID = -8642936855413034232L;
    private static final String DEFAULT_AGENT_PREFIX = "fish";

    private final String cloudName;
    private transient Set<Queue.Executable> executables = new HashSet<>();

    private UUID application_uid;

    protected AquariumSlave(String name, String nodeDescription, String cloudName, String labelStr,
                            ComputerLauncher computerLauncher) throws Descriptor.FormException, IOException {
        super(name, null, computerLauncher);
        this.setMode(Mode.EXCLUSIVE);
        this.setNodeDescription(nodeDescription);
        this.setNumExecutors(1);
        this.setLabelString(labelStr);
        this.cloudName = cloudName;
    }

    public String getCloudName() {
        return cloudName;
    }

    public UUID getApplicationUID() {
        return this.application_uid;
    }

    @Override
    public String getRemoteFS() {
        return Util.fixNull(remoteFS);
    }

    // Copied from Slave#getRootPath because this uses the underlying field
    @CheckForNull
    @Override
    public FilePath getRootPath() {
        final SlaveComputer computer = getComputer();
        if (computer == null) {
            // if computer is null then channel is null and thus we were going to return null anyway
            return null;
        } else {
            return createPath(StringUtils.defaultString(computer.getAbsoluteRemoteFs(), getRemoteFS()));
        }
    }

    /**
     * Returns the cloud instance which created this agent.
     * @return the cloud instance which created this agent.
     * @throws IllegalStateException if the cloud doesn't exist anymore, or is not a {@link AquariumCloud}.
     */
    @Nonnull
    public AquariumCloud getAquariumCloud() {
        return getAquariumCloud(getCloudName());
    }

    public void setApplicationUID(UUID uid) {
        this.application_uid = uid;
    }

    private static AquariumCloud getAquariumCloud(String cloudName) {
        Cloud cloud = Jenkins.get().getCloud(cloudName);
        if (cloud instanceof AquariumCloud) {
            return (AquariumCloud) cloud;
        } else {
            throw new IllegalStateException(AquariumCloud.class.getName()
                    + " can be launched only by instances of " + AquariumCloud.class.getName()
                    + ". Cloud is " + cloud.getClass().getName());
        }
    }

    static String getSlaveName() {
        String randString = RandomStringUtils.random(8, "bcdfghjklmnpqrstvwxz0123456789");
        return String.format("%s-%s", DEFAULT_AGENT_PREFIX, randString);
    }

    @Override
    synchronized protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        LOG.log(Level.INFO, "Terminating Aquarium resource for agent {0}", this.name);

        AquariumCloud cloud;
        try {
            cloud = getAquariumCloud();
        } catch (IllegalStateException e) {
            String msg = String.format("Unable to terminate agent %s Application %s: %s. Cloud may have been removed." +
                    " There may be leftover resources on the Aquarium cluster.", this.name, this.application_uid, e);
            e.printStackTrace(listener.fatalError(msg));
            LOG.log(Level.SEVERE, msg);
            return;
        }
        cloud.onTerminate(this);

        Computer computer = toComputer();
        if (computer == null) {
            String msg = String.format("Computer for agent is null: %s", this.name);
            LOG.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }
        if( !(computer.getOfflineCause() instanceof AquariumOfflineCause) ) {
            computer.disconnect(new AquariumOfflineCause());
            LOG.log(Level.INFO, "Disconnected computer for node '" + name + "'.");
        }

        // Tell the slave to stop JNLP reconnects.
        VirtualChannel ch = computer.getChannel();
        if (ch != null) {
            Future<Void> disconnectorFuture = ch.callAsync(new SlaveDisconnector());
            try {
                disconnectorFuture.get(DISCONNECTION_TIMEOUT, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                String msg = String.format("Ignoring error sending order to not reconnect agent %s: %s", this.name, e.getMessage());
                LOG.log(Level.INFO, msg, e);
            }
        }

        if (getCloudName() == null) {
            String msg = String.format("Cloud name is not set for agent, can't terminate: %s", this.name);
            LOG.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }

        // Need to make sure the resource will be deallocated even if there will be some issues with network
        while( true ) {
            try {
                if( this.application_uid != null ) {
                    ApplicationState state = cloud.getClient().applicationStateGet(this.application_uid);
                    if (state.getStatus() != ApplicationStatus.ALLOCATED
                            && state.getStatus() != ApplicationStatus.ELECTED
                            && state.getStatus() != ApplicationStatus.NEW) {
                        LOG.log(Level.SEVERE, "The Application is not active: " + state.getStatus());
                        break;
                    }
                    cloud.getClient().applicationDeallocate(this.application_uid);
                }
                break;
            } catch( ApiException e ) {
                if( e.getCode() == 404 ) {
                    String msg = String.format("Failed to remove resource from %s for agent %s Application %s: %s.",
                            getCloudName(), this.name, this.application_uid, e.getMessage());
                    LOG.log(Level.SEVERE, msg);
                    break;
                }
                String msg = String.format("Failed to remove resource from %s for agent %s Application %s: %s." +
                        " Repeating...", getCloudName(), this.name, this.application_uid, e.getMessage());
                LOG.log(Level.SEVERE, msg);
            } catch( Exception e ) {
                String msg = String.format("Error during remove resource from %s for agent %s Application %s: %s.",
                        getCloudName(), this.name, this.application_uid, e);
                e.printStackTrace(listener.fatalError(msg));
                LOG.log(Level.SEVERE, msg);
                break;
            }
            Thread.sleep(5000);
        }

        String msg = String.format("Disconnected computer %s", name);
        LOG.log(Level.INFO, msg);
        listener.getLogger().println(msg);
    }

    @Override
    public String toString() {
        return String.format("AquariumSlave name: %s", name);
    }

    @Override
    public boolean equals(Object o) {
        if( this == o ) return true;
        if( o == null || getClass() != o.getClass() ) return false;
        if( !super.equals(o) ) return false;
        AquariumSlave that = (AquariumSlave) o;
        return cloudName.equals(that.cloudName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), cloudName);
    }

    @Override
    public AquariumComputer createComputer() {
        return new AquariumComputer(this);
    }

    @NotNull
    @Override
    public Launcher createLauncher(TaskListener listener) {
        Launcher launcher = super.createLauncher(listener);
        return launcher;
    }

    protected Object readResolve() {
        this.executables = new HashSet<>();
        return this;
    }

    /**
     * Returns a new {@link Builder} instance.
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a {@link AquariumSlave} instance.
     */
    public static class Builder {
        private String name;
        private String nodeDescription;
        private List<String> labels = new ArrayList<>();
        private AquariumCloud cloud;
        private ComputerLauncher computerLauncher;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder nodeDescription(String nodeDescription) {
            this.nodeDescription = nodeDescription;
            return this;
        }

        public Builder cloud(AquariumCloud cloud) {
            this.cloud = cloud;
            return this;
        }

        public Builder addLabel(String label) {
            if( label != null && !label.isEmpty() ) {
                this.labels.add(label);
            }
            return this;
        }

        public Builder computerLauncher(ComputerLauncher computerLauncher) {
            this.computerLauncher = computerLauncher;
            return this;
        }

        public AquariumSlave build() throws IOException, Descriptor.FormException {
            Validate.notNull(cloud);
            return new AquariumSlave(
                    name == null ? getSlaveName() : name,
                    nodeDescription == null ? "Aquarium agent" : nodeDescription,
                    cloud.getName(),
                    labels == null ? "no_label_provided" : String.join(" ", labels),
                    computerLauncher == null ? defaultLauncher() : computerLauncher);
        }

        private AquariumLauncher defaultLauncher() {
            AquariumLauncher launcher = new AquariumLauncher(null, null);
            return launcher;
        }
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Aquarium Agent";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }

    }

    private static class SlaveDisconnector extends MasterToSlaveCallable<Void, IOException> {

        private static final long serialVersionUID = 8683427258340193283L;

		private static final Logger LOG = Logger.getLogger(SlaveDisconnector.class.getName());

        @Override
        public Void call() throws IOException {
            Engine e = Engine.current();
            // No engine, do nothing.
            if (e == null) {
                return null;
            }
            // Tell the slave JNLP agent to not attempt further reconnects.
            e.setNoReconnect(true);
            LOG.log(Level.INFO, "Disabled slave engine reconnects.");
            return null;
        }
    }

    public static class AquariumOfflineCause extends OfflineCause {
        @Override
        public String toString() {
            return "Deallocating Aquarium resource";
        }
    }
}
