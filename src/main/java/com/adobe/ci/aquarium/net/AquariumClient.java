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
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringEscapeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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

    public Application applicationCreate(String label_name, String jenkins_url, String agent_name, String agent_secret) throws Exception {
        // TODO: not actually optimal to get all the labels, better the latest and only ID.
        List<Label> labels = labelFind(label_name);
        if( labels.isEmpty() )
            throw new Exception("Application create unable find label " + label_name);

        Application app = new Application();

        JSONObject metadata = new JSONObject();
        metadata.put("JENKINS_URL", jenkins_url);
        metadata.put("JENKINS_AGENT_NAME", agent_name);
        metadata.put("JENKINS_AGENT_SECRET", agent_secret);

        app.setMetadata(metadata);
        // Sorting the labels by version and using the max one
        app.setLabelID(labels.stream().max(Comparator.comparing(l -> l.getVersion())).get().getID());

        return new ApplicationApi(api_client_pool.get(0)).applicationCreatePost(app);
    }

    public ApplicationState applicationStateGet(Long app_id) throws Exception {
        startConnection();
        return new ApplicationApi(api_client_pool.get(0)).applicationStateGet(app_id);
    }

    public void applicationDeallocate(Long app_id) throws Exception {
        startConnection();
        new ApplicationApi(api_client_pool.get(0)).applicationDeallocateGet(app_id);
    }

    public User meGet() throws Exception {
        startConnection();
        return new UserApi(api_client_pool.get(0)).userMeGet();
    }
}
