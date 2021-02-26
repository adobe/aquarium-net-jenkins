package com.adobe.ci.aquarium.net;

import static org.apache.commons.lang.StringUtils.isEmpty;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.common.util.concurrent.Futures;
import hudson.Util;
import hudson.model.*;
import hudson.security.ACL;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.Extension;
import hudson.slaves.Cloud;

import java.io.IOException;
import java.lang.String;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.CheckForNull;

class ServiceInstance {}

public class AquariumCloud extends Cloud {

    private static final Logger LOG = Logger.getLogger(AquariumCloud.class.getName());

    private String initHostUrl;
    @CheckForNull
    private String credentialsId;
    private String jenkinsUrl;

    @DataBoundConstructor
    public AquariumCloud(String name) {
        super(name);
        LOG.log(Level.INFO, "STARTING Aquarium CLOUD");
    }

    public String getName() {
        return name;
    }

    public String getInitHostUrl() {
        return initHostUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getJenkinsUrl() {
        return initHostUrl;
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
    public void setJenkinsUrl(String value) {
        this.jenkinsUrl = Util.fixEmptyAndTrim(value);
    }

    @Override
    public boolean canProvision(Label label) {
        LOG.log(Level.INFO, "Can provision label? : " + label.getName());
        return label.getName().equals("xcode12.2"); // TODO: for demo
    }

    private PlannedNode buildAgent(String label) {
        Future f;
        String displayName;
        try {
            AquariumSlave agent = AquariumSlave
                    .builder().cloud(this).label(label).build();
            displayName = agent.getDisplayName();
            f = Futures.immediateFuture(agent);
        } catch (IOException | Descriptor.FormException e) {
            displayName = null;
            f = Futures.immediateFailedFuture(e);
        }
        return new PlannedNode(Util.fixNull(displayName), f, 1);
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
        LOG.log(Level.INFO, "Execute provision : " + label.getName() + ", workload: " + excessWorkload);

        Set<String> allInProvisioning = getInProvisioning(label); // Nodes being launched
        LOG.log(Level.INFO, () -> "In provisioning : " + allInProvisioning);
        int toBeProvisioned = Math.max(0, excessWorkload - allInProvisioning.size());
        LOG.log(Level.INFO, "Label \"{0}\" excess workload: {1}", new Object[] {label, toBeProvisioned});

        List<PlannedNode> plannedNodes = new ArrayList<>();
        while (toBeProvisioned > 0/* && Limits */) {
            plannedNodes.add(buildAgent(label.getName()));
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

    public static String getBasicAuthCreds(String credentialsId) {
        StandardUsernamePasswordCredentials c = (StandardUsernamePasswordCredentials)CredentialsMatchers.firstOrNull(
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
                                               @QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (StringUtils.isBlank(name))
                return FormValidation.error("name is required");

            URL url = null;
            try {
                url = new URL(initHostUrl);
                String url_path = StringUtils.stripEnd(url.getPath(), "/") + "/api/v1/resource/";
                if( url.getQuery() != null )
                    url_path += "?" + url.getQuery();
                url = new URL(url.getProtocol(), url.getHost(), url.getPort(), url_path, null);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                LOG.log(Level.INFO, "Check connection: " + url);

                con.setRequestProperty("Authorization", "Basic " + getBasicAuthCreds(credentialsId));

                con.setRequestMethod("GET");
                con.setDoOutput(false);
                int status = con.getResponseCode();
                con.disconnect();
                return FormValidation.ok("Connected to Aquarium Fish node (HTTP %d)", status);
            } catch (Exception e) {
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
    }
}
