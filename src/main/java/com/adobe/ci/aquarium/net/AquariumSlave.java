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

package com.adobe.ci.aquarium.net;

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
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
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
    private static final String DEFAULT_AGENT_PREFIX = "jenkins-agent";

    private final String cloudName;
    private transient Set<Queue.Executable> executables = new HashSet<>();

    private Long application_id;

    protected AquariumSlave(String name, String nodeDescription, String cloudName, String labelStr,
                            ComputerLauncher computerLauncher) throws Descriptor.FormException, IOException {
        super(name, null, computerLauncher);
        this.setNodeDescription(nodeDescription);
        this.setNumExecutors(1);
        this.setLabelString(labelStr);
        this.cloudName = cloudName;
    }

    public String getCloudName() {
        return cloudName;
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

    public void setApplicationId(Long id) {
        this.application_id = id;
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
        String randString = RandomStringUtils.random(5, "bcdfghjklmnpqrstvwxz0123456789");
        return String.format("%s-%s", DEFAULT_AGENT_PREFIX,  randString);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        LOG.log(Level.INFO, "Terminating Aquarium resource for agent {0}", name);

        AquariumCloud cloud;
        try {
            cloud = getAquariumCloud();
        } catch (IllegalStateException e) {
            e.printStackTrace(listener.fatalError("Unable to terminate agent. Cloud may have been removed. There may be leftover resources on the Aquarium cluster."));
            LOG.log(Level.SEVERE, String.format("Unable to terminate agent %s. Cloud may have been removed. There may be leftover resources on the Aquarium cluster.", name));
            return;
        }
        cloud.onTerminate(this);

        Computer computer = toComputer();
        if (computer == null) {
            String msg = String.format("Computer for agent is null: %s", name);
            LOG.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }

        // Tell the slave to stop JNLP reconnects.
        VirtualChannel ch = computer.getChannel();
        if (ch != null) {
            Future<Void> disconnectorFuture = ch.callAsync(new SlaveDisconnector());
            try {
                disconnectorFuture.get(DISCONNECTION_TIMEOUT, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                String msg = String.format("Ignoring error sending order to not reconnect agent %s: %s", name, e.getMessage());
                LOG.log(Level.INFO, msg, e);
            }
        }

        if (getCloudName() == null) {
            String msg = String.format("Cloud name is not set for agent, can't terminate: %s", name);
            LOG.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }

        try {
            if( this.application_id != null ) {
                cloud.getClient().applicationDeallocate(this.application_id);
            }
        } catch (Exception e) {
            String msg = String.format("Failed to remove resource from %s. There may be leftover resources on the Aquarium cluster.", getCloudName());
            e.printStackTrace(listener.fatalError(msg));
            LOG.log(Level.SEVERE, msg);
            return;
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
        private String label;
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

        public Builder label(String label) {
            this.label = label;
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
                    label == null ? "no_label_provided" : label,
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

}
