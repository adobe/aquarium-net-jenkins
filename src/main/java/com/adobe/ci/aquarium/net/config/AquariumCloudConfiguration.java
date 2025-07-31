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

import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Configuration class for Aquarium Fish Cloud plugin.
 * Contains all the settings needed to connect to and interact with the Fish cluster.
 */
public class AquariumCloudConfiguration {
    private final boolean enabled;
    private final String initHost;
    private final String username;
    private final Secret password;
    private final String certificate;
    private final int agentConnectionWaitMinutes;
    private final String jenkinsUrl;
    private final String additionalMetadata;
    private final String labelFilter;

    @DataBoundConstructor
    public AquariumCloudConfiguration(boolean enabled,
                                    String initHost,
                                    String username,
                                    Secret password,
                                    String certificate,
                                    int agentConnectionWaitMinutes,
                                    String jenkinsUrl,
                                    String additionalMetadata,
                                    String labelFilter) {
        this.enabled = enabled;
        this.initHost = initHost;
        this.username = username;
        this.password = password;
        this.certificate = certificate;
        this.agentConnectionWaitMinutes = agentConnectionWaitMinutes > 0 ? agentConnectionWaitMinutes : 10;
        this.jenkinsUrl = jenkinsUrl;
        this.additionalMetadata = additionalMetadata;
        this.labelFilter = labelFilter;
    }

    // Getters
    public boolean isEnabled() {
        return enabled;
    }

    public String getInitHost() {
        return initHost;
    }

    public String getUsername() {
        return username;
    }

    public Secret getPassword() {
        return password;
    }

    public String getPasswordPlainText() {
        return password != null ? password.getPlainText() : null;
    }

    public String getCertificate() {
        return certificate;
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

    /**
     * Builder pattern for creating configuration instances
     */
    public static class Builder {
        private boolean enabled = true;
        private String initHost;
        private String username;
        private String password;
        private String certificate;
        private int agentConnectionWaitMinutes = 10;
        private String jenkinsUrl;
        private String additionalMetadata;
        private String labelFilter;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder initHost(String initHost) {
            this.initHost = initHost;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder certificate(String certificate) {
            this.certificate = certificate;
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

        public AquariumCloudConfiguration build() {
            return new AquariumCloudConfiguration(
                enabled, initHost, username,
                password != null ? Secret.fromString(password) : null,
                certificate, agentConnectionWaitMinutes, jenkinsUrl,
                additionalMetadata, labelFilter
            );
        }
    }

    @Override
    public String toString() {
        return "AquariumCloudConfiguration{" +
                "enabled=" + enabled +
                ", initHost='" + initHost + '\'' +
                ", username='" + username + '\'' +
                ", agentConnectionWaitMinutes=" + agentConnectionWaitMinutes +
                ", jenkinsUrl='" + jenkinsUrl + '\'' +
                ", labelFilter='" + labelFilter + '\'' +
                '}';
    }
}
