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

import com.adobe.ci.aquarium.fish.client.ApiClient;
import com.adobe.ci.aquarium.fish.client.api.ApplicationApi;
import com.adobe.ci.aquarium.fish.client.api.LabelApi;
import com.adobe.ci.aquarium.fish.client.api.UserApi;
import com.adobe.ci.aquarium.fish.client.model.Application;
import com.adobe.ci.aquarium.fish.client.model.ApplicationState;
import com.adobe.ci.aquarium.fish.client.model.Label;
import com.adobe.ci.aquarium.fish.client.model.User;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringEscapeUtils;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

public class AquariumClient {
    String node_url; // TODO: replace with nodes pool
    String cred_id;
    String ca_cert_id;

    List<ApiClient> api_client_pool = new ArrayList<ApiClient>();

    AquariumClient(String url, String credentials_id, String ca_cert_id) {
        this.node_url = url;
        this.cred_id = credentials_id;
        this.ca_cert_id = ca_cert_id;

        startConnection();
    }

    private void startConnection() {
        if( api_client_pool.size() < 1 ) {
            ApiClient cl = new ApiClient();
            cl.setBasePath(this.node_url);
            cl.setUsername(getBasicAuthCreds(this.cred_id).getUsername());
            cl.setPassword(getBasicAuthCreds(this.cred_id).getPassword().getPlainText());
            if( this.ca_cert_id == null || this.ca_cert_id.isEmpty() ) {
                cl.setVerifyingSsl(false);
            } else {
                try {
                    cl.setSslCaCert(getCaAuthCreds(this.ca_cert_id).getContent());
                } catch( Exception e ) {}
            }
            api_client_pool.add(cl);
        }
    }

    private static StandardUsernamePasswordCredentials getBasicAuthCreds(String credentialsId) {
        StandardUsernamePasswordCredentials c = (StandardUsernamePasswordCredentials) CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        Collections.emptyList()
                ),
                CredentialsMatchers.allOf(
                        CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                        CredentialsMatchers.withId(credentialsId)
                )
        );

        return c;
    }

    private static FileCredentials getCaAuthCreds(String credentialsId) {
        FileCredentials c = (FileCredentials) CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        Collections.emptyList()
                ),
                CredentialsMatchers.allOf(
                        CredentialsMatchers.instanceOf(FileCredentials.class),
                        CredentialsMatchers.withId(credentialsId)
                )
        );

        return c;
    }

    public List<Label> labelFind(String name) throws Exception {
        startConnection();
        return new LabelApi(api_client_pool.get(0)).labelListGet("name='" + StringEscapeUtils.escapeSql(name) + "'");
    }

    public Application applicationCreate(String label_name, String jenkins_url, String agent_name, String agent_secret, String add_metadata) throws Exception {
        // TODO: not actually optimal to get all the labels, better the latest and only ID.
        List<Label> labels = labelFind(label_name);
        if( labels.isEmpty() )
            throw new Exception("Application create unable find label " + label_name);

        Application app = new Application();

        JSONObject metadata = new JSONObject();
        metadata.put("JENKINS_URL", jenkins_url);
        metadata.put("JENKINS_AGENT_NAME", agent_name);
        metadata.put("JENKINS_AGENT_SECRET", agent_secret);

        if( add_metadata != null && ! add_metadata.isEmpty() ) {
            try (BufferedReader reader = new BufferedReader(new StringReader(add_metadata))) {
                String line = reader.readLine();
                while (line != null) {
                    String[] line_sep = line.split("=", 2);
                    if (line_sep.length == 2) {
                        metadata.put(line_sep[0], line_sep[1]);
                    }
                    line = reader.readLine();
                }
            } catch (IOException exc) {
                // nop
            }
        }

        app.setMetadata(metadata);
        // Sorting the labels by version and using the max one
        app.setLabelUID(labels.stream().max(Comparator.comparing(l -> l.getVersion())).get().getUID());

        return new ApplicationApi(api_client_pool.get(0)).applicationCreatePost(app);
    }

    public ApplicationState applicationStateGet(UUID app_uid) throws Exception {
        startConnection();
        return new ApplicationApi(api_client_pool.get(0)).applicationStateGet(app_uid);
    }

    public void applicationSnapshot(UUID app_uid, Boolean full) throws Exception {
        startConnection();
        new ApplicationApi(api_client_pool.get(0)).applicationSnapshotGet(app_uid, full);
    }

    public void applicationDeallocate(UUID app_uid) throws Exception {
        startConnection();
        new ApplicationApi(api_client_pool.get(0)).applicationDeallocateGet(app_uid);
    }

    public User meGet() throws Exception {
        startConnection();
        return new UserApi(api_client_pool.get(0)).userMeGet();
    }
}
