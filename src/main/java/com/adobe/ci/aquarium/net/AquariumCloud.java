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

import com.adobe.ci.aquarium.fish.client.model.User;
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
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.apache.commons.lang.StringUtils.isEmpty;

class ServiceInstance {}

public class AquariumCloud extends Cloud {

    private static final Logger LOG = Logger.getLogger(AquariumCloud.class.getName());

    private String initHostUrl;
    @CheckForNull
    private String credentialsId;
    private String caCredentialsId;
    private String jenkinsUrl;
    private String metadata;
    private List<LabelMapping> labelMappings = new ArrayList<>();

    // A collection of labels supported by the Auqarium Fish cluster
    private Set<LabelAtom> labelsCached;
    private long labelsCachedUpdateTime = 0;

    @DataBoundConstructor
    public AquariumCloud(String name) {
        super(name);
        LOG.log(Level.INFO, "STARTING Aquarium CLOUD");
    }

    // Used by jelly
    public String getName() {
        return name;
    }

    // Used by jelly
    public String getInitHostUrl() {
        return initHostUrl;
    }

    // Used by jelly
    public String getCredentialsId() { return credentialsId; }

    // Used by jelly
    public String getCaCredentialsId() { return caCredentialsId; }

    // Used by jelly
    public String getJenkinsUrl() { return jenkinsUrl; }

    // Used by jelly
    public String getMetadata() { return metadata; }

    // Used by jelly
    public List<LabelMapping> getLabelMappings() {
        return labelMappings;
    }

    public AquariumClient getClient() {
        return new AquariumClient(this.initHostUrl, this.credentialsId, this.caCredentialsId);
    }

    @DataBoundSetter
    public void setInitHostUrl(String value) {
        this.initHostUrl = Util.fixEmptyAndTrim(value);
    }

    @DataBoundSetter
    public void setCredentialsId(String value) {
        credentialsId = Util.fixEmpty(value);
    }

    @DataBoundSetter
    public void setCaCredentialsId(String value) {
        caCredentialsId = Util.fixEmpty(value);
    }

    @DataBoundSetter
    public void setJenkinsUrl(String value) {
        this.jenkinsUrl = Util.fixEmptyAndTrim(value);
    }

    @DataBoundSetter
    public void setMetadata(String value) {
        metadata = Util.fixEmpty(value);
    }

    @DataBoundSetter
    public void setLabelMappings(@CheckForNull List<LabelMapping> labels) {
        this.labelMappings = new ArrayList<>();
        if( labels != null ) {
            this.labelMappings.addAll(labels);
        }
    }

    @Override
    public boolean canProvision(Label label) {
        try {
            // Update the cache if time has come
            if( this.labelsCachedUpdateTime < System.currentTimeMillis() ) {
                this.updateLabelsCache();
            }

            // Simple comparison by label name
            if( this.labelsCached.contains(label) )
                return true;

            // Match of the label expression
            return label.matches(this.labelsCached);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    public void updateLabelsCache() throws Exception {
        // Request all the labels supported by the cluster
        Set<LabelAtom> out = new HashSet<>();
        this.getClient().labelGet().forEach(label -> {
            out.add(LabelAtom.get(label.getName()));
        });

        if( out.size() == 0 ) {
            LOG.log(Level.WARNING, "Cluster contains no labels - empty list was returned");
        }

        // Replace the cached labels with newly received
        this.labelsCached = out;

        // Set the next labels update cache time to 30 mins from now
        this.labelsCachedUpdateTime = System.currentTimeMillis() + 1800000;
    }

    private PlannedNode buildAgent(String label) {
        Future<Node> future;
        String displayName;
        try {
            // Make sure the aquarium requested label is first in the label list
            AquariumSlave agent = AquariumSlave.builder().cloud(this)
                    .addLabel(label).addLabel(LabelMapping.getLabels(this.labelMappings, label)).build();
            displayName = agent.getDisplayName();
            future = Futures.immediateFuture(agent);
        } catch (IOException | Descriptor.FormException e) {
            displayName = null;
            future = Futures.immediateFailedFuture(e);
        }
        return new PlannedNode(Util.fixNull(displayName), future, 1);
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

        // Check if there is already enough provisioned nodes to cover the need of the queue to not overshoot the demand
        // Unfortunately jenkins can request much more agents than needed - but with Aquarium it's not up to Jenkins
        // anymore to decide on this matter, because it's not the resource manager anymore. So we need to allocate only
        // the actually required amount of resources for the provided label.
        Set<String> allInProvisioning = getInProvisioning(label); // Nodes being launched
        int actualExcessWorkload = Jenkins.get().getQueue().countBuildableItemsFor(label);
        int toBeProvisioned = Math.min(excessWorkload, actualExcessWorkload - allInProvisioning.size());
        if( toBeProvisioned <= 0 )
            return plannedNodes; // No need to provision anything

        LOG.log(Level.INFO, "In provisioning : " + allInProvisioning + " and we need to add: " + toBeProvisioned + "(total required: " + actualExcessWorkload + ")");

        // Find first label that is matching to the requested expression
        String label_name = "";
        Set<LabelAtom> set = new HashSet<LabelAtom>();
        for( LabelAtom l : this.labelsCached ) {
            set.clear();
            set.add(l);
            if( label.matches(set) ) {
                label_name = l.getName();
                break;
            }
        }
        LOG.log(Level.INFO, "Chosen label : " + label_name);

        while( toBeProvisioned > 0 /* && Limits */ ) {
            plannedNodes.add(buildAgent(label_name));
            toBeProvisioned--;
        }
        return plannedNodes;
    }

    @Override
    public String toString() {
        return "AquariumCloud {Name='" + name + "'}";
    }

    public ServiceInstance getSI() {
        return new ServiceInstance();
    }

    public void onTerminate(AquariumSlave slave) {
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "Aquarium";
        }

        @RequirePOST
        @SuppressWarnings("unused") // used by jelly
        public FormValidation doTestConnection(@QueryParameter String name,
                                               @QueryParameter String initHostUrl,
                                               @QueryParameter String credentialsId,
                                               @QueryParameter String caCredentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (StringUtils.isBlank(name))
                return FormValidation.error("name is required");

            URL url = null;
            try {
                User me = new AquariumClient(initHostUrl, credentialsId, caCredentialsId).meGet();
                // Request went with no exceptions - so we're good
                return FormValidation.ok("Connected to Aquarium Fish node as '%s'", me.getName());
            } catch( Exception e ) {
                LOG.log(Level.WARNING, String.format("Error testing connection %s", url), e);
                return FormValidation.error("Error testing connection %s: %s", initHostUrl, e.getMessage());
            }
        }

        @SuppressWarnings("unused") // used by jelly
        public FormValidation doCheckInitHostUrl(@QueryParameter String value) {
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
                    serverUrl != null ? URIRequirementBuilder.fromUri(serverUrl).build()
                            : Collections.EMPTY_LIST,
                    CredentialsMatchers.anyOf(
                            CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)
                    )
            );
            return result;
        }

        @RequirePOST
        @SuppressWarnings("unused") // used by jelly
        public ListBoxModel doFillCaCredentialsIdItems(@AncestorInPath ItemGroup context, @QueryParameter String serverUrl) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            StandardListBoxModel result = new StandardListBoxModel();
            result.includeEmptyValue();
            result.includeMatchingAs(
                    ACL.SYSTEM,
                    context,
                    StandardCredentials.class,
                    serverUrl != null ? URIRequirementBuilder.fromUri(serverUrl).build()
                            : Collections.EMPTY_LIST,
                    CredentialsMatchers.anyOf(
                            CredentialsMatchers.instanceOf(FileCredentials.class)
                    )
            );
            return result;
        }
    }
}
