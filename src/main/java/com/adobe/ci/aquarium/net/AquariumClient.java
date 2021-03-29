package com.adobe.ci.aquarium.net;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class AquariumClient {
    String node_url; // TODO: replace with nodes pool
    String cred_id;

    AquariumClient(String url, String credentials_id) {
        this.node_url = url;
        this.cred_id = credentials_id;
    }

    public static String getBasicAuthCreds(String credentialsId) {
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

        String auth = c.getUsername() + ":" + c.getPassword().getPlainText();
        byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(StandardCharsets.UTF_8));

        return new String(encodedAuth);
    }

    private HttpURLConnection request(String path) throws Exception {
        URL url = new URL(this.node_url);
        String url_path = StringUtils.stripEnd(url.getPath(), "/") + "/api/v1/" + path;
        if( url.getQuery() != null )
            url_path += "?" + url.getQuery();
        url = new URL(url.getProtocol(), url.getHost(), url.getPort(), url_path, null);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();

        con.setRequestProperty("Authorization", "Basic " + getBasicAuthCreds(this.cred_id));
        con.setRequestProperty("Content-Type", "application/json; utf-8");
        con.setRequestProperty("Accept", "application/json");

        return con;
    }

    public JSONObject labelFind(String name) throws Exception {
        // TODO: do search on the cluster side
        HttpURLConnection con = request("label/");
        con.setRequestMethod("GET");
        con.setDoOutput(false);

        int status = con.getResponseCode();
        if( status != 200 ) {
            throw new Exception("Label find response code is " + status);
        }

        JSONObject out = parseJson(con);
        JSONArray list = out.getJSONArray("data");

        for( Object obj : list ) {
            if( ((JSONObject)obj).getString("name").equals(name) ) {
                return (JSONObject)obj;
            }
        }

        con.disconnect();

        return new JSONObject();
    }

    public JSONObject labelGet(Integer id) throws Exception {
        HttpURLConnection con = request("label/"+id);
        con.setRequestMethod("GET");
        con.setDoOutput(false);

        int status = con.getResponseCode();
        if( status != 200 ) {
            throw new Exception("ApplicationStatus get response code is " + status);
        }

        JSONObject out = parseJson(con);

        con.disconnect();

        return out.getJSONObject("data");
    }

    public JSONObject applicationCreate(String label_name, String jenkins_url, String agent_name, String agent_secret) throws Exception {
        JSONObject label = labelFind(label_name);
        if( label.isEmpty() )
            throw new Exception("Application create unable find label " + label_name);
        Integer label_id = label.getInt("ID");

        HttpURLConnection con = request("application/");
        con.setRequestMethod("POST");
        con.setDoOutput(true);

        JSONObject metadata = new JSONObject();
        metadata.put("JENKINS_URL", jenkins_url);
        metadata.put("JENKINS_AGENT_NAME", agent_name);
        metadata.put("JENKINS_AGENT_SECRET", agent_secret);

        JSONObject json_data = new JSONObject();
        json_data.put("label_id", label_id);
        json_data.put("metadata", metadata);

        try(OutputStream os = con.getOutputStream()) {
            byte[] input = json_data.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int status = con.getResponseCode();
        if( status != 200 ) {
            throw new Exception("Application create response code is " + status);
        }

        JSONObject out = parseJson(con);

        con.disconnect();

        return out.getJSONObject("data");
    }

    public JSONObject applicationStatusGet(Integer app_id) throws Exception {
        HttpURLConnection con = request("application/"+app_id+"/status");
        con.setRequestMethod("GET");
        con.setDoOutput(false);

        int status = con.getResponseCode();
        if( status != 200 ) {
            throw new Exception("ApplicationStatus get response code is " + status);
        }

        JSONObject out = parseJson(con);

        con.disconnect();

        return out.getJSONObject("data");
    }

    public void applicationDeallocate(Integer app_id) throws Exception {
        HttpURLConnection con = request("application/"+app_id+"/deallocate");
        con.setRequestMethod("GET");
        con.setDoOutput(false);

        int status = con.getResponseCode();
        if( status != 200 ) {
            throw new Exception("Application deallocate response code is " + status);
        }

        con.disconnect();
    }

    public JSONObject meGet() throws Exception {
        HttpURLConnection con = request("me/");
        con.setRequestMethod("GET");
        con.setDoOutput(false);

        int status = con.getResponseCode();
        if( status != 200 ) {
            throw new Exception("Me get response code is " + status);
        }

        JSONObject out = parseJson(con);

        con.disconnect();

        return out.getJSONObject("data");
    }

    private JSONObject parseJson(HttpURLConnection con) throws Exception {
        // TODO: Make json parser to parse input stream directly
        StringBuilder result = new StringBuilder();

        InputStream in = new BufferedInputStream(con.getInputStream());
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));

        String line;
        while ((line = reader.readLine()) != null) {
            result.append(line);
        }

        return JSONObject.fromObject(result.toString());
    }
}
