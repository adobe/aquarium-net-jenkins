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

package com.adobe.ci.aquarium.net.config;

import org.kohsuke.stapler.DataBoundConstructor;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;

import java.util.ArrayList;
import java.io.IOException;

/**
 * Configuration class for Aquarium Fish Cloud plugin.
 * Contains all the settings needed to connect to and interact with the Fish cluster.
 */
public class AquariumCloudConfiguration {
    private final boolean enabled;
    private final String initAddress;
    private final String credentialsId;
    private final String certificateId;
    private final int agentConnectionWaitMinutes;
    private final String jenkinsUrl;
    private final String additionalMetadata;
    private final String labelFilter;
    private final java.util.List<AquariumLabelTemplate> labelTemplates;

    @DataBoundConstructor
    public AquariumCloudConfiguration(boolean enabled,
                                    String initAddress,
                                    String credentialsId,
                                    String certificateId,
                                    int agentConnectionWaitMinutes,
                                    String jenkinsUrl,
                                    String additionalMetadata,
                                    String labelFilter,
                                    java.util.List<AquariumLabelTemplate> labelTemplates) {
        this.enabled = enabled;
        this.initAddress = initAddress;
        this.credentialsId = credentialsId;
        this.certificateId = certificateId;
        this.agentConnectionWaitMinutes = agentConnectionWaitMinutes > 0 ? agentConnectionWaitMinutes : 10;
        this.jenkinsUrl = jenkinsUrl;
        this.additionalMetadata = additionalMetadata;
        this.labelFilter = labelFilter;
        this.labelTemplates = labelTemplates != null ? new java.util.ArrayList<>(labelTemplates) : new java.util.ArrayList<>();
    }

    // Getters
    public boolean isEnabled() {
        return enabled;
    }

    public String getInitAddress() {
        return initAddress;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getCertificateId() {
        return certificateId;
    }

    public String getCredentialsUsername() {
        if (StringUtils.isBlank(credentialsId)) {
            return null;
        }
        StandardUsernamePasswordCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        new ArrayList<>()
                ),
                CredentialsMatchers.withId(credentialsId)
        );
        return credentials != null ? credentials.getUsername() : null;
    }

    public String getCredentialsPassword() {
        if (StringUtils.isBlank(credentialsId)) {
            return null;
        }
        StandardUsernamePasswordCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        new ArrayList<>()
                ),
                CredentialsMatchers.withId(credentialsId)
        );
        return credentials != null ? credentials.getPassword().getPlainText() : null;
    }

    public byte[] getCertificate() {
        if (StringUtils.isBlank(certificateId)) {
            return null;
        }
        FileCredentials fileCredentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        FileCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        new ArrayList<>()
                ),
                CredentialsMatchers.withId(certificateId)
        );
        if (fileCredentials != null) {
            try {
                java.io.InputStream is = fileCredentials.getContent();
                byte[] bytes = new byte[is.available()];
                is.read(bytes);
                return bytes;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public int getAgentConnectionWaitMinutes() {
        return agentConnectionWaitMinutes;
    }

    public String getJenkinsUrl() {
        return jenkinsUrl;
    }

    public String getAdditionalMetadata() {
        return additionalMetadata;
    }

    public String getLabelFilter() {
        return labelFilter;
    }

    public java.util.List<AquariumLabelTemplate> getLabelTemplates() {
        return new java.util.ArrayList<>(labelTemplates);
    }

    /**
     * Builder pattern for creating configuration instances
     */
    public static class Builder {
        private boolean enabled = true;
        private String initAddress;
        private String credentialsId;
        private String certificateId;
        private int agentConnectionWaitMinutes = 10;
        private String jenkinsUrl;
        private String additionalMetadata;
        private String labelFilter;
        private java.util.List<AquariumLabelTemplate> labelTemplates = new java.util.ArrayList<>();

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder initAddress(String initAddress) {
            this.initAddress = initAddress;
            return this;
        }

        public Builder credentialsId(String credentialsId) {
            this.credentialsId = credentialsId;
            return this;
        }

        public Builder certificateId(String certificateId) {
            this.certificateId = certificateId;
            return this;
        }

        public Builder agentConnectionWaitMinutes(int minutes) {
            this.agentConnectionWaitMinutes = minutes;
            return this;
        }

        public Builder jenkinsUrl(String jenkinsUrl) {
            this.jenkinsUrl = jenkinsUrl;
            return this;
        }

        public Builder additionalMetadata(String additionalMetadata) {
            this.additionalMetadata = additionalMetadata;
            return this;
        }

        public Builder labelFilter(String labelFilter) {
            this.labelFilter = labelFilter;
            return this;
        }

        public Builder labelTemplates(java.util.List<AquariumLabelTemplate> labelTemplates) {
            this.labelTemplates = labelTemplates != null ? new java.util.ArrayList<>(labelTemplates) : new java.util.ArrayList<>();
            return this;
        }

        public AquariumCloudConfiguration build() {
            return new AquariumCloudConfiguration(
                enabled, initAddress, credentialsId,
                certificateId, agentConnectionWaitMinutes, jenkinsUrl,
                additionalMetadata, labelFilter, labelTemplates
            );
        }
    }

    @Override
    public String toString() {
        return "AquariumCloudConfiguration{" +
                "enabled=" + enabled +
                ", initAddress='" + initAddress + '\'' +
                ", credentialsId='" + credentialsId + '\'' +
                ", certificateId='" + certificateId + '\'' +
                ", agentConnectionWaitMinutes=" + agentConnectionWaitMinutes +
                ", jenkinsUrl='" + jenkinsUrl + '\'' +
                ", labelFilter='" + labelFilter + '\'' +
                '}';
    }
}
