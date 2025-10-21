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

// Author: Sergei Parshev (@sparshev)

package com.adobe.ci.aquarium.net;

import com.adobe.ci.aquarium.net.config.AquariumCloudConfiguration;
import com.adobe.ci.aquarium.net.config.AquariumLabelTemplate;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.common.util.concurrent.Futures;
import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import aquarium.v2.UserOuterClass;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import hudson.slaves.SlaveComputer;

import static org.apache.commons.lang.StringUtils.isEmpty;

public class AquariumCloud extends Cloud {

    private static final Logger LOG = Logger.getLogger(AquariumCloud.class.getName());

    private static final int DEFAULT_AGENT_CONNECTION_WAIT_MIN = 10;

    private boolean enabled = true;
    private String initAddressUrl;
    @CheckForNull
    private String credentialsId;
    private String certificateId;
    private Integer agentConnectWaitMin;
    private String jenkinsUrl;
    private String metadata;
    private String labelFilter;
    private List<LabelMapping> labelMappings = new ArrayList<>();
    private List<AquariumLabelTemplate> labelTemplates = new ArrayList<>();

    // A collection of labels supported by the Aquarium Fish cluster
    private transient Map<String, com.adobe.ci.aquarium.net.model.Label> fishLabelsCache = new ConcurrentHashMap<>();
    private Set<LabelAtom> labelsCached;

    private transient AquariumClient client;
    private transient volatile boolean connected = false;
    private transient boolean reconnectClient = true;

    // Track connection timeout timers by agent node name for proper cleanup
    private transient Map<String, java.util.TimerTask> agentConnectionTimers = new ConcurrentHashMap<>();

    // Track application state listeners by agent node name for proper cleanup
    private transient Map<String, AquariumClient.ApplicationStateListener> applicationStateListeners = new ConcurrentHashMap<>();

    @DataBoundConstructor
    public AquariumCloud(String name) {
        super(name);
        this.agentConnectionTimers = new ConcurrentHashMap<>();
        this.applicationStateListeners = new ConcurrentHashMap<>();
        LOG.info("STARTING Aquarium CLOUD from name");
    }

    /**
     * Constructor for integration tests
     * @param name - name of the cloud
     * @param config - configuration for connection to the Aquarium Fish node
     */
    public AquariumCloud(String name, AquariumCloudConfiguration config) {
        super(name);
        this.agentConnectionTimers = new ConcurrentHashMap<>();
        this.applicationStateListeners = new ConcurrentHashMap<>();
        this.enabled = config.isEnabled();
        this.initAddressUrl = config.getInitAddress();
        this.credentialsId = config.getCredentialsId();
        this.certificateId = config.getCertificateId();
        this.agentConnectWaitMin = config.getAgentConnectionWaitMinutes();
        this.jenkinsUrl = config.getJenkinsUrl();
        this.metadata = config.getAdditionalMetadata();
        this.labelFilter = config.getLabelFilter();
        this.labelTemplates = config.getLabelTemplates();
        LOG.info("STARTING Aquarium CLOUD with config");
        // In integration tests, avoid background reconnection to keep test isolation
        this.reconnectClient = false;
        initializeConnection();
    }

    /**
     * Ensure transient fields are initialized after deserialization
     */
    protected Object readResolve() {
        if (fishLabelsCache == null) {
            fishLabelsCache = new ConcurrentHashMap<>();
        }
        if (agentConnectionTimers == null) {
            agentConnectionTimers = new ConcurrentHashMap<>();
        }
        if (applicationStateListeners == null) {
            applicationStateListeners = new ConcurrentHashMap<>();
        }
        LOG.info("STARTING Aquarium CLOUD from serialized");
        return this;
    }

    /**
     * Initialize the client connection and start streaming if enabled
     */
    private void initializeConnection() {
        if (!enabled || initAddressUrl == null) {
            LOG.info("Aquarium cloud" + name + " is disabled, skip connection");
            return;
        }

        try {
            if (client != null) {
                // Check if already connected
                if (client.isConnected()) {
                    LOG.log(Level.FINE, "Client already connected for cloud " + name);
                    return;
                }
                // Try to reconnect existing client
                try {
                    client.connect();
                    connected = true;
                    refreshLabels();
                    LOG.log(Level.INFO, "Reconnected to Aquarium Fish node for cloud " + name);
                    return;
                } catch (Exception e) {
                    // Connection failed, clean up and create new client
                    LOG.log(Level.WARNING, "Failed to reconnect existing client, creating new one", e);
                    try {
                        client.shutdown();
                    } catch (Exception ex) {
                        LOG.log(Level.WARNING, "Error shutting down old client", ex);
                    }
                    client = null;
                }
            }

            // Create new client
            client = newClient();
            setupLabelListeners();
            client.connect();
            connected = true;
            refreshLabels();
            LOG.log(Level.INFO, "Connected to Aquarium Fish node for cloud " + name);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to connect to Aquarium Fish node for cloud '" + name + "'", e);
            connected = false;
        }
    }

    /**
     * Ensure the connection to the Aquarium Fish node is established
     */
    public void ensureConnected() {
        // Initialize connection if needed
        if (!connected) {
            initializeConnection();
        }
    }

    /**
     * Setup listeners for label changes from the streaming service
     */
    private void setupLabelListeners() {
        client.addLabelChangeListener(new AquariumClient.LabelChangeListener() {
            @Override
            public void onLabelCreated(com.adobe.ci.aquarium.net.model.Label label) {
                if (matchesLabelFilter(label.getName())) {
                    fishLabelsCache.put(label.getUid(), label);
                    updateJenkinsLabelsCache();
                    LOG.log(Level.INFO, "Added Fish label: " + label.getName() + ":" + label.getVersion() + " (" + label.getUid() + ")");
                }
            }

            @Override
            public void onLabelUpdated(com.adobe.ci.aquarium.net.model.Label label) {
                if (matchesLabelFilter(label.getName())) {
                    fishLabelsCache.put(label.getUid(), label);
                    updateJenkinsLabelsCache();
                    LOG.log(Level.INFO, "Updated Fish label: " + label.getName() + ":" + label.getVersion() + " (" + label.getUid() + ")");
                }
            }

            @Override
            public void onLabelRemoved(String labelUid) {
                com.adobe.ci.aquarium.net.model.Label label = fishLabelsCache.remove(labelUid);
                if (label != null) {
                    updateJenkinsLabelsCache();
                    LOG.log(Level.INFO, "Removed Fish label: " + label.getName() + ":" + label.getVersion() + " (" + label.getUid() + ")");
                }
            }
        });

        client.addConnectionStatusListener(new AquariumClient.ConnectionStatusListener() {
            @Override
            public void onConnectionStatusChanged(boolean isConnected) {
                connected = isConnected;
                if (isConnected) {
                    LOG.log(Level.INFO, "Reconnected to Aquarium Fish node for cloud " + name);
                    refreshLabels();
                } else {
                    LOG.log(Level.WARNING, "Lost connection to Aquarium Fish node for cloud " + name);
                }
            }
        });
    }

    /**
     * Check if a label name matches the configured filter pattern
     */
    private boolean matchesLabelFilter(String labelName) {
        if (labelFilter == null || labelFilter.trim().isEmpty()) {
            return true; // No filter means all labels are allowed
        }
        try {
            return Pattern.matches(labelFilter, labelName);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Invalid label filter pattern '" + labelFilter + "': " + e.getMessage());
            return true; // Invalid pattern means allow all
        }
    }

    /**
     * Refresh labels from the streaming service
     */
    private void refreshLabels() {
        try {
            List<com.adobe.ci.aquarium.net.model.Label> labels = client.listLabels();
            if (fishLabelsCache == null) {
                this.fishLabelsCache = new ConcurrentHashMap<>();
            }
            fishLabelsCache.clear();
            for (com.adobe.ci.aquarium.net.model.Label label : labels) {
                if (matchesLabelFilter(label.getName())) {
                    fishLabelsCache.put(label.getUid(), label);
                }
            }
            updateJenkinsLabelsCache();
            LOG.log(Level.INFO, "Refreshed " + fishLabelsCache.size() + " Fish labels for cloud '" + name + "'");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to refresh labels from Aquarium Fish node", e);
        }
    }

    /**
     * Update the Jenkins labels cache from Fish labels
     */
    private void updateJenkinsLabelsCache() {
        Set<LabelAtom> newLabels = new HashSet<>();
        for (com.adobe.ci.aquarium.net.model.Label fishLabel : fishLabelsCache.values()) {
            // Add the base label
            newLabels.add(LabelAtom.get(fishLabel.getName()));
            // Add versioned label
            newLabels.add(LabelAtom.get(fishLabel.getName() + ":" + fishLabel.getVersion()));
        }
        labelsCached = newLabels;
        LOG.log(Level.FINE, "Updated Jenkins labels cache with " + newLabels.size() + " labels");
    }

    // Used by jelly
    public String getName() { return this.name; }

    // Used by jelly
    public String getInitAddressUrl() { return this.initAddressUrl; }

    // Used by jelly
    @Nullable
    public String getCredentialsId() { return this.credentialsId; }

    // Used by jelly
    @Nullable
    public String getCertificateId() { return this.certificateId; }

    public int getAgentConnectWaitMin() {
        if( this.agentConnectWaitMin == null || this.agentConnectWaitMin < 0 ) {
            return DEFAULT_AGENT_CONNECTION_WAIT_MIN;
        }
        return this.agentConnectWaitMin;
    }

    // Used by jelly
    public String getJenkinsUrl() { return this.jenkinsUrl; }

    // Used by jelly
    public String getMetadata() { return this.metadata; }

    // Used by jelly
    public boolean isEnabled() { return this.enabled; }

    // Used by jelly
    public String getLabelFilter() { return this.labelFilter; }

    // Used by jelly
    public List<LabelMapping> getLabelMappings() {
        return this.labelMappings;
    }

    // Used by jelly
    public List<AquariumLabelTemplate> getLabelTemplates() {
        return this.labelTemplates;
    }

    // Used by AquariumCloudLabelsAction
    public Map<String, com.adobe.ci.aquarium.net.model.Label> getFishLabelsCache() {
        return new HashMap<>(fishLabelsCache);
    }

    // Used by AquariumCloudLabelsAction
    public boolean isConnected() {
        return connected;
    }

    public AquariumClient newClient() {
        String address = "localhost:8001";
        try {
            URL url = new URL(this.initAddressUrl);
            address = url.getHost() + ":" + url.getPort();
        } catch (MalformedURLException e) {
            LOG.log(Level.WARNING, "Invalid initAddressUrl, using default localhost:8001: " + this.initAddressUrl, e);
        }
        AquariumCloudConfiguration config = new AquariumCloudConfiguration.Builder()
                .enabled(this.enabled)
                .initAddress(address)
                .credentialsId(this.getCredentialsId())
                .certificateId(this.getCertificateId())
                .agentConnectionWaitMinutes(this.getAgentConnectWaitMin())
                .jenkinsUrl(this.getJenkinsUrl())
                .additionalMetadata(this.getMetadata())
                .labelFilter(this.getLabelFilter())
                .build();
        return new AquariumClient(config, this.reconnectClient);
    }

    public AquariumClient getClient() {
        return this.client;
    }

    @DataBoundSetter
    public void setInitAddressUrl(String value) {
        this.initAddressUrl = Util.fixEmptyAndTrim(value);
    }

    @DataBoundSetter
    public void setCredentialsId(String value) {
        this.credentialsId = Util.fixEmpty(value);
    }

    @DataBoundSetter
    public void setCertificateId(String value) {
        this.certificateId = Util.fixEmpty(value);
    }

    @DataBoundSetter
    public void setAgentConnectWaitMin(Integer value) {
        this.agentConnectWaitMin = value;
    }

    @DataBoundSetter
    public void setJenkinsUrl(String value) {
        this.jenkinsUrl = Util.fixEmptyAndTrim(value);
    }

    @DataBoundSetter
    public void setMetadata(String value) {
        this.metadata = Util.fixEmpty(value);
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = this.enabled;
        this.enabled = enabled;

        // Handle state change
        if (wasEnabled && !enabled) {
            // Cloud was disabled - initiate graceful shutdown
            LOG.log(Level.INFO, "Aquarium cloud " + name + " disabled, initiating graceful shutdown");
            initiateGracefulShutdown();
        } else if (!wasEnabled && enabled) {
            // Cloud was enabled - initialize connection
            LOG.log(Level.INFO, "Aquarium cloud " + name + " enabled, initializing connection");
            initializeConnection();
        }
    }

    /**
     * Initiate graceful shutdown when cloud is disabled
     */
    private void initiateGracefulShutdown() {
        // Check if there are any active nodes
        List<AquariumSlave> activeNodes = getActiveAquariumNodes();

        if (activeNodes.isEmpty()) {
            // No active nodes, disconnect immediately
            disconnectFromFish();
        } else {
            // Active nodes exist, wait for them to complete
            LOG.log(Level.INFO, "Found " + activeNodes.size() + " active nodes, waiting for completion before shutdown");

            // Set up monitoring for node completion
            scheduleShutdownCheck(activeNodes);
        }
    }

    /**
     * Get all active Aquarium nodes from this cloud
     */
    private List<AquariumSlave> getActiveAquariumNodes() {
        List<AquariumSlave> activeNodes = new ArrayList<>();

        for (hudson.model.Node node : Jenkins.get().getNodes()) {
            if (node instanceof AquariumSlave) {
                AquariumSlave aquariumSlave = (AquariumSlave) node;
                // Check if this node belongs to our cloud and is active
                if (name.equals(aquariumSlave.getCloudName()) && isNodeActive(aquariumSlave)) {
                    activeNodes.add(aquariumSlave);
                }
            }
        }

        return activeNodes;
    }

    /**
     * Check if a node is considered active (has running builds or is online)
     */
    private boolean isNodeActive(AquariumSlave node) {
        hudson.model.Computer computer = node.toComputer();
        if (computer == null) {
            return false;
        }

        // Node is active if it's online or has executors with running builds
        return computer.isOnline() || computer.countBusy() > 0;
    }

    /**
     * Schedule periodic checks for shutdown readiness
     */
    private void scheduleShutdownCheck(List<AquariumSlave> initialActiveNodes) {
        java.util.Timer shutdownTimer = new java.util.Timer("AquariumCloudShutdown-" + name, true);

        shutdownTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                List<AquariumSlave> currentActiveNodes = getActiveAquariumNodes();

                if (currentActiveNodes.isEmpty()) {
                    LOG.log(Level.INFO, "All nodes completed, disconnecting from Fish node");
                    disconnectFromFish();
                    this.cancel(); // Stop the timer
                } else {
                    LOG.log(Level.INFO, "Still waiting for " + currentActiveNodes.size() + " nodes to complete");
                }
            }
        }, 30000, 30000); // Check every 30 seconds
    }

    /**
     * Disconnect from Fish node and cleanup resources
     */
    private void disconnectFromFish() {
        // Cancel all pending connection timeout timers
        for (Map.Entry<String, java.util.TimerTask> entry : agentConnectionTimers.entrySet()) {
            try {
                entry.getValue().cancel();
                LOG.log(Level.FINE, "Cancelled connection timeout timer for agent " + entry.getKey());
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error cancelling timer for agent " + entry.getKey(), e);
            }
        }
        agentConnectionTimers.clear();

        // Remove all application state listeners
        if (client != null) {
            for (Map.Entry<String, AquariumClient.ApplicationStateListener> entry : applicationStateListeners.entrySet()) {
                try {
                    client.removeApplicationStateListener(entry.getValue());
                    LOG.log(Level.FINE, "Removed application state listener for agent " + entry.getKey());
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Error removing listener for agent " + entry.getKey(), e);
                }
            }
        }
        applicationStateListeners.clear();

        if (client != null) {
            try {
                client.shutdown();
                LOG.log(Level.INFO, "Disconnected from Aquarium Fish node for cloud " + name);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error disconnecting from Fish node", e);
            } finally {
                client = null;
                connected = false;
                fishLabelsCache.clear();
                labelsCached = null;
            }
        }
    }

    @DataBoundSetter
    public void setLabelFilter(String labelFilter) {
        this.labelFilter = Util.fixEmptyAndTrim(labelFilter);
    }

    @DataBoundSetter
    public void setLabelMappings(@CheckForNull List<LabelMapping> labels) {
        this.labelMappings = new ArrayList<>();
        if( labels != null ) {
            this.labelMappings.addAll(labels);
        }
    }

    @DataBoundSetter
    public void setLabelTemplates(@CheckForNull List<AquariumLabelTemplate> templates) {
        this.labelTemplates = new ArrayList<>();
        if( templates != null ) {
            this.labelTemplates.addAll(templates);
        }
    }

    @Override
    public boolean canProvision(Label label) {
        LOG.log(Level.FINEST, "Can provision label? " + label);

        // Check if cloud is enabled
        if (!enabled) {
            LOG.log(Level.FINEST, "Cloud is disabled, cannot provision");
            return false;
        }

        // Initialize connection if needed
        if (!connected) {
            initializeConnection();
        }

        try {
            // If we have no labels cached, return false
            if (this.labelsCached == null || this.labelsCached.isEmpty()) {
                LOG.log(Level.INFO, "No labels cached.");
                return false;
            }

            // Prepare label since it can contain :version in any part of the expression
            if (label.toString().contains(":")) {
                // Modify label to cut-out the versions for now
                label = Label.parseExpression(label.getExpression().replaceAll(":[0-9]+", ""));
                LOG.finest("Modified label: " + label);
            }

            // Match of the label expression
            return label.matches(this.labelsCached);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error in canProvision for label " + label, e);
        }

        return false;
    }

    /**
     * Legacy method - replaced by streaming label updates
     * @deprecated Use streaming label updates instead
     */
    @Deprecated
    public void updateLabelsCache() throws Exception {
        // Delegate to the new streaming method
        if (connected && client != null) {
            refreshLabels();
        }
    }

    /**
     * Planning agent to create with non-blocking application provisioning
     * @param fishLabel Fish label name, that could contain version separated by ":"
     * @return PlannedNode which will be connected from the agent in the future
     */
    private PlannedNode buildAgent(String fishLabel) {
        Future<Node> future;
        String displayName;

        try {
            // Create the Jenkins agent first
            AquariumSlave agent = AquariumSlave.builder().cloud(this)
                    .addLabel(fishLabel)
                    .addLabel(LabelMapping.getLabels(this.labelMappings, fishLabel))
                    .build();
            displayName = agent.getDisplayName();
            future = Futures.immediateFuture(agent);
        } catch (IOException | Descriptor.FormException e) {
            displayName = null;
            future = Futures.immediateFailedFuture(e);
        }
        return new PlannedNode(Util.fixNull(displayName), future, 1);
    }

    /**
     * Start asynchronous application creation and monitoring
     */
    public void startApplicationCreation(AquariumSlave agent, String fishLabel) {
        // Find the Fish label UID
        com.adobe.ci.aquarium.net.model.Label fishLabelObj = null;
        String fishLabelName = fishLabel.split(":")[0];
        Integer fishLabelVersion = null;
        if (fishLabel.contains(":")) {
            fishLabelVersion = Integer.parseInt(fishLabel.split(":")[1]);
        }
        // Picking the right label version from the list
        for (com.adobe.ci.aquarium.net.model.Label label : fishLabelsCache.values()) {
            if (label.getName().equals(fishLabelName)) {
                if (fishLabelVersion == null) {
                    // Looking for the latest label version
                    if (fishLabelObj == null || label.getVersion() > fishLabelObj.getVersion()) {
                        fishLabelObj = label;
                    }
                } else {
                    // Looking for the exact version
                    if (label.getVersion() == fishLabelVersion) {
                        fishLabelObj = label;
                        break;
                    }
                }
            }
        }

        if (fishLabelObj == null) {
            LOG.log(Level.SEVERE, "Fish label not found in cache: " + fishLabelName + ":" + fishLabelVersion);
            try {
                agent.terminate();
            } catch (IOException | InterruptedException e) {
                LOG.log(Level.WARNING, "Error terminating agent", e);
            }
            return;
        }

        try {
            // Create application request
            aquarium.v2.ApplicationOuterClass.Application.Builder appBuilder =
                aquarium.v2.ApplicationOuterClass.Application.newBuilder()
                    .setLabelUid(fishLabelObj.getUid());

            // Add metadata
            Map<String, String> metadata = new HashMap<>();
            metadata.put("JENKINS_URL", getJenkinsUrl());
            metadata.put("JENKINS_AGENT_NAME", agent.getNodeName());
            metadata.put("JENKINS_AGENT_SECRET", getJnlpSecret(agent));

            // Add additional metadata from configuration
            if (this.metadata != null && !this.metadata.trim().isEmpty()) {
                try {
                    // Simple JSON parsing for additional metadata
                    // TODO: Use proper JSON parser if needed
                    String[] lines = this.metadata.split("\\n");
                    for (String line : lines) {
                        line = line.trim();
                        if (line.contains(":")) {
                            String[] parts = line.split(":", 2);
                            if (parts.length == 2) {
                                String key = parts[0].trim().replaceAll("[\"']", "");
                                String value = parts[1].trim().replaceAll("[\"',]", "");
                                metadata.put(key, value);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to parse additional metadata", e);
                }
            }

            // Convert metadata to protobuf Struct
            com.google.protobuf.Struct.Builder metadataBuilder = com.google.protobuf.Struct.newBuilder();
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                LOG.fine("Adding metadata: " + entry.getKey() + "=" + entry.getValue());
                metadataBuilder.putFields(entry.getKey(),
                    com.google.protobuf.Value.newBuilder().setStringValue(entry.getValue()).build());
            }
            appBuilder.setMetadata(metadataBuilder.build());

            // Send application creation request via streaming
            com.adobe.ci.aquarium.net.model.Application application = client.createApplication(appBuilder.build());

            // Store application UID in agent for later reference - convert to UUID
            try {
                agent.setApplicationUID(java.util.UUID.fromString(application.getUid()));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to parse application UID as UUID: " + application.getUid(), e);
            }

            // Set up application state monitoring
            setupApplicationStateMonitoring(agent, application.getUid());

            LOG.log(Level.INFO, "Started application creation for agent " + agent.getNodeName() +
                    " with application UID: " + application.getUid());

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to create application for agent " + agent.getNodeName(), e);
            try {
                agent.terminate();
            } catch (IOException | InterruptedException ex) {
                LOG.log(Level.WARNING, "Error terminating agent", ex);
            }
        }
    }

    /**
     * Setup monitoring for application state changes
     */
    private void setupApplicationStateMonitoring(AquariumSlave agent, String applicationUid) {
        String nodeName = agent.getNodeName();

        // Remove any existing listener for this agent (shouldn't happen, but be safe)
        removeApplicationStateListener(nodeName);

        AquariumClient.ApplicationStateListener listener = client.monitorApplicationState(applicationUid, (applicationState) -> {
            LOG.log(Level.INFO, "Application " + applicationUid + " state changed to: " +
                    applicationState.getStatus() + " for agent " + nodeName);

            switch (applicationState.getStatus()) {
                case ALLOCATED:
                    // Application is ready, start agent connection timeout
                    startAgentConnectionTimeout(agent);
                    break;

                case ERROR:
                    LOG.log(Level.SEVERE, "Application " + applicationUid + " failed: " +
                            applicationState.getDescription());
                    // Clean up listener since this is a terminal state
                    removeApplicationStateListener(nodeName);
                    try {
                        agent.terminate();
                    } catch (IOException | InterruptedException e) {
                        LOG.log(Level.WARNING, "Error terminating agent", e);
                    }
                    break;

                case DEALLOCATED:
                    LOG.log(Level.INFO, "Application " + applicationUid + " was deallocated");
                    // Clean up listener since this is a terminal state
                    removeApplicationStateListener(nodeName);
                    try {
                        agent.terminate();
                    } catch (IOException | InterruptedException e) {
                        LOG.log(Level.WARNING, "Error terminating agent", e);
                    }
                    break;

                default:
                    // Other states like PENDING, PROVISIONING - just log and wait
                    LOG.log(Level.FINE, "Application " + applicationUid + " in state: " +
                            applicationState.getStatus());
                    break;
            }
        });

        // Store the listener for cleanup when agent is terminated
        applicationStateListeners.put(nodeName, listener);
    }

    /**
     * Remove and cleanup application state listener for a specific agent
     */
    private void removeApplicationStateListener(String nodeName) {
        AquariumClient.ApplicationStateListener listener = applicationStateListeners.remove(nodeName);
        if (listener != null && client != null) {
            client.removeApplicationStateListener(listener);
            LOG.log(Level.FINE, "Removed application state listener for agent " + nodeName);
        }
    }



    private static boolean isNotAcceptingTasks(Node n) {
        Computer computer = n.toComputer();
        return computer != null && (computer.isLaunchSupported() // Launcher hasn't been called yet
                || !n.isAcceptingTasks()); // node is not ready yet
    }

    public Set<String> getInProvisioning(@CheckForNull Label label) {
        if (label != null) {
            return label.getNodes().stream()
                    .filter(AquariumSlave.class::isInstance)
                    .filter(AquariumCloud::isNotAcceptingTasks)
                    .map(Node::getNodeName)
                    .collect(Collectors.toSet());
        } else {
            return Collections.emptySet();
        }
    }

    @Override
    public Collection<PlannedNode> provision(Label label, int excessWorkload) {
        List<PlannedNode> plannedNodes = new ArrayList<>();

        LOG.log(Level.INFO, "Execute provision : " + label.toString() + ", requested amount: " + excessWorkload);

        // Check if cloud is enabled
        if (!enabled) {
            LOG.log(Level.INFO, "Cloud is disabled, skip provision");
            return plannedNodes;
        }

        // Initialize connection if needed
        if (!connected) {
            initializeConnection();
        }

        if (!connected || client == null) {
            LOG.log(Level.WARNING, "Not connected to Aquarium Fish, cannot provision");
            return plannedNodes;
        }

        // Check if there is already enough provisioned nodes to cover the need of the queue
        Set<String> allInProvisioning = getInProvisioning(label); // Nodes being launched
        int actualExcessWorkload = Jenkins.get().getQueue().countBuildableItemsFor(label);
        if (actualExcessWorkload == 0) {
            LOG.fine("No queue excess workload, setting to: " + excessWorkload);
            actualExcessWorkload = excessWorkload;
        }
        int toBeProvisioned = Math.min(excessWorkload, actualExcessWorkload - allInProvisioning.size());
        if (toBeProvisioned <= 0) {
            LOG.fine("No need to provision additional nodes, already provisioning: " + allInProvisioning);
            return plannedNodes; // No need to provision anything
        }

        LOG.log(Level.INFO, "In provisioning : " + allInProvisioning + " and we need to add: " + toBeProvisioned + " (total required: " + actualExcessWorkload + ")");

        // Create planned nodes - each will start application creation asynchronously
        while (toBeProvisioned > 0) {
            plannedNodes.add(buildAgent(label.toString()));
            toBeProvisioned--;
        }
        return plannedNodes;
    }

    /**
     * Find a Fish label that matches the Jenkins label expression
     */
    private com.adobe.ci.aquarium.net.model.Label findMatchingFishLabel(Label jenkinsLabel) {
        // Parse the labels to cut-out versions for matching
        Label labelNoVersions = jenkinsLabel;
        boolean versionsInLabel = jenkinsLabel.toString().contains(":");
        if (versionsInLabel) {
            try {
                labelNoVersions = Label.parseExpression(jenkinsLabel.getExpression().replaceAll(":[0-9]+", ""));
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Unable to cut-out versions from the label '" + jenkinsLabel + "'", e);
            }
        }

        // Find first Fish label that matches the requested expression
        for (com.adobe.ci.aquarium.net.model.Label fishLabel : fishLabelsCache.values()) {
            Set<LabelAtom> testSet = new HashSet<>();
            testSet.add(LabelAtom.get(fishLabel.getName()));
            if (labelNoVersions.matches(testSet)) {
                return fishLabel;
            }
        }
        return null;
    }

    /**
     * Extract version number from Jenkins label if specified
     */
    private Integer extractVersionFromLabel(Label jenkinsLabel, String fishLabelName) {
        for (LabelAtom atom : jenkinsLabel.listAtoms()) {
            String atomStr = atom.toString();
            if (atomStr.startsWith(fishLabelName) && atomStr.contains(":")) {
                int colonPos = atomStr.indexOf(':');
                if (atomStr.length() > colonPos + 1) {
                    try {
                        return Integer.parseInt(atomStr.substring(colonPos + 1));
                    } catch (NumberFormatException e) {
                        LOG.log(Level.WARNING, "Invalid version format in label: " + atomStr);
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "AquariumCloud {Name='" + name + "'}";
    }

    public void onTerminate(AquariumSlave slave) {
        String nodeName = slave.getNodeName();

        // Cancel any pending connection timeout timer for this agent
        cancelAgentConnectionTimer(nodeName);

        // Remove application state listener for this agent
        removeApplicationStateListener(nodeName);

        // Handle slave termination - deallocate application if connected
        if (connected && client != null) {
            try {
                // Get application UID from slave if available
                UUID applicationUID = slave.getApplicationUID();
                if (applicationUID != null) {
                    client.deallocateApplication(applicationUID.toString());
                    LOG.log(Level.INFO, "Deallocated application " + applicationUID + " for terminated agent " + nodeName);
                } else {
                    LOG.log(Level.FINE, "No application UID set for agent " + nodeName + ", skipping deallocation");
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to deallocate application for terminated agent " + nodeName, e);
            }
        } else {
            LOG.log(Level.FINE, "Not connected to Fish, cannot deallocate application for agent " + nodeName);
        }
    }

    /**
     * Cancel the connection timeout timer for a specific agent
     */
    private void cancelAgentConnectionTimer(String nodeName) {
        java.util.TimerTask timer = agentConnectionTimers.remove(nodeName);
        if (timer != null) {
            timer.cancel();
            LOG.log(Level.FINE, "Cancelled connection timeout timer for agent " + nodeName);
        }
    }

    /**
     * Get JNLP secret for an agent
     */
    private String getJnlpSecret(AquariumSlave agent) throws Exception {
        // Jenkins generates JNLP secrets automatically for agents
        // We can access it through the agent's computer
        Computer computer = agent.toComputer();
        LOG.finest("Getting JNLP secret for agent: " + agent.getNodeName() + ", computer: " + computer);
        if (computer != null && computer instanceof SlaveComputer) {
            SlaveComputer slaveComputer = (SlaveComputer) computer;
            return slaveComputer.getJnlpMac();
        }
        throw new Exception("Failed to get JNLP secret for agent: " + agent.getNodeName());
    }

    /**
     * Start agent connection timeout monitoring
     */
    private void startAgentConnectionTimeout(AquariumSlave agent) {
        String nodeName = agent.getNodeName();
        LOG.log(Level.INFO, "Application allocated for agent " + nodeName + ", starting connection timeout");

        // Cancel any existing timer for this agent (shouldn't happen, but be safe)
        cancelAgentConnectionTimer(nodeName);

        // Schedule a timeout check
        long timeoutMs = getAgentConnectWaitMin() * 60L * 1000L; // Convert minutes to milliseconds

        java.util.Timer timer = new java.util.Timer("AquariumAgentTimeout-" + nodeName, true);
        java.util.TimerTask task = new java.util.TimerTask() {
            @Override
            public void run() {
                try {
                    // Remove from tracking map since we're executing
                    agentConnectionTimers.remove(nodeName);

                    // Check if agent still exists in Jenkins
                    hudson.model.Node node = Jenkins.get().getNode(nodeName);
                    if (node == null) {
                        LOG.log(Level.FINE, "Agent " + nodeName + " no longer exists, skipping timeout check");
                        return;
                    }

                    // Check if agent is connected
                    Computer computer = agent.toComputer();
                    if (computer == null) {
                        LOG.log(Level.FINE, "Computer is null for agent " + nodeName + ", node may have been removed");
                        return;
                    }

                    if (!computer.isOnline()) {
                        LOG.log(Level.WARNING, "Agent " + nodeName + " failed to connect within timeout, terminating");
                        try {
                            agent.terminate();
                        } catch (Exception e) {
                            LOG.log(Level.SEVERE, "Failed to terminate agent " + nodeName, e);
                        }
                    } else {
                        LOG.log(Level.FINE, "Agent " + nodeName + " is online, timeout check passed");
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Error in connection timeout check for agent " + nodeName, e);
                }
            }
        };

        // Store the task for later cancellation if needed
        agentConnectionTimers.put(nodeName, task);
        timer.schedule(task, timeoutMs);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Aquarium";
        }

        @RequirePOST
        @SuppressWarnings("unused") // used by jelly
        public FormValidation doTestConnection(@QueryParameter String name,
                                               @QueryParameter String initAddressUrl,
                                               @QueryParameter String credentialsId,
                                               @QueryParameter String certificateId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (StringUtils.isBlank(name))
                return FormValidation.error("name is required");

            try {
                URL address = new URL(initAddressUrl);
                AquariumCloudConfiguration config = new AquariumCloudConfiguration.Builder()
                        .initAddress(address.getHost() + ":" + address.getPort())
                        .credentialsId(credentialsId)
                        .certificateId(certificateId)
                        .build();
                AquariumClient client = new AquariumClient(config, false);
                client.connect();
                UserOuterClass.User user = client.getMe();
                client.disconnect();
                // Request went with no exceptions - so we're good
                return FormValidation.ok("Connected to Aquarium Fish node successfully as " + user.getName());
            } catch( Exception e ) {
                LOG.log(Level.WARNING, String.format("Error testing connection %s", initAddressUrl), e);
                Throwable rootCause = e;
                while (rootCause.getCause() != null) {
                    rootCause = rootCause.getCause();
                }
                return FormValidation.error("Error testing connection %s: %s", initAddressUrl, rootCause.getMessage());
            }
        }

        @SuppressWarnings("unused") // used by jelly
        public FormValidation doCheckInitAddressUrl(@QueryParameter String value) {
            try {
                if( !isEmpty(value) ) {
                    new URL(value);
                } else {
                    return FormValidation.error("Empty Aquarium Fish node URL");
                }
            } catch (MalformedURLException e) {
                return FormValidation.error(e, "Invalid Aquarium Fish node URL");
            }
            return FormValidation.ok();
        }

        @SuppressWarnings("unused") // used by jelly
        public FormValidation doCheckJenkinsUrl(@QueryParameter String value) {
            try {
                if( !isEmpty(value) ) new URL(value);
            } catch (MalformedURLException e) {
                return FormValidation.error(e, "Invalid Jenkins URL");
            }
            return FormValidation.ok();
        }

        @RequirePOST
        @SuppressWarnings("unused") // used by jelly
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context, @QueryParameter String serverUrl) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            StandardListBoxModel result = new StandardListBoxModel();
            result.includeEmptyValue();
            result.includeMatchingAs(
                    ACL.SYSTEM,
                    context,
                    StandardCredentials.class,
                    serverUrl != null ? URIRequirementBuilder.fromUri(serverUrl).build() : Collections.emptyList(),
                    CredentialsMatchers.anyOf(
                            CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)
                    )
            );
            return result;
        }

        @RequirePOST
        @SuppressWarnings("unused") // used by jelly
        public ListBoxModel doFillCertificateIdItems(@AncestorInPath ItemGroup context, @QueryParameter String serverUrl) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            StandardListBoxModel result = new StandardListBoxModel();
            result.includeEmptyValue();
            result.includeMatchingAs(
                    ACL.SYSTEM,
                    context,
                    StandardCredentials.class,
                    serverUrl != null ? URIRequirementBuilder.fromUri(serverUrl).build() : Collections.emptyList(),
                    CredentialsMatchers.anyOf(
                            CredentialsMatchers.instanceOf(FileCredentials.class)
                    )
            );
            return result;
        }
    }
}
