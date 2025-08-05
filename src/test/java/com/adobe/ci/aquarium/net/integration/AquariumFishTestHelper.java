package com.adobe.ci.aquarium.net.integration;

import aquarium.v2.*;
import com.adobe.ci.aquarium.net.AquariumClient;
import com.adobe.ci.aquarium.net.config.AquariumCloudConfiguration;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.rules.ExternalResource;
import org.jvnet.hudson.test.JenkinsRule;
import hudson.model.ModelObject;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SecretBytes;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import org.jenkinsci.plugins.plaincredentials.FileCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.CredentialsProvider;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import static org.junit.Assert.*;

/**
 * Test helper for managing Aquarium Fish nodes in integration tests.
 * Handles node startup, configuration, user creation, and cleanup.
 */
public class AquariumFishTestHelper extends ExternalResource {
    private static final Logger LOGGER = Logger.getLogger(AquariumFishTestHelper.class.getName());

    private Process fishProcess;
    private Path workspace;
    private String adminToken;
    private String apiEndpoint;
    private X509Certificate caCertificate;
    private ManagedChannel channel;
    private UserServiceGrpc.UserServiceBlockingStub userStub;
    private LabelServiceGrpc.LabelServiceBlockingStub labelStub;
    private ApplicationServiceGrpc.ApplicationServiceBlockingStub applicationStub;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ExecutorService logReader = Executors.newSingleThreadExecutor();
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private final AtomicReference<String> initError = new AtomicReference<>();

    @Override
    protected void before() throws Throwable {
        startFishNode();
        createTestUser();
    }

    @Override
    protected void after() {
        stopFishNode();
    }

    /**
     * Start the Aquarium Fish node with test configuration
     */
    public void startFishNode() throws Exception {
        LOGGER.info("Starting Aquarium Fish node...");

        // Create workspace
        workspace = Files.createTempDirectory("fish-test-");
        LOGGER.info("Created workspace: " + workspace);

        // Create configuration file
        String config = createTestConfig();
        Path configFile = workspace.resolve("config.yml");
        Files.write(configFile, config.getBytes());
        LOGGER.info("Created config file: " + configFile);

        // Find fish binary
        String fishPath = findFishBinary();
        LOGGER.info("Using fish binary: " + fishPath);

        // Start fish process
        ProcessBuilder pb = new ProcessBuilder(
            fishPath, "-v", "debug", "-c", configFile.toString()
        );
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(true);

        fishProcess = pb.start();
        isRunning.set(true);

        // Start log reader
        startLogReader();

        // Wait for initialization
        if (!initLatch.await(60, TimeUnit.SECONDS)) {
            throw new RuntimeException("Fish node failed to initialize within 60 seconds");
        }

        String error = initError.get();
        if (error != null) {
            throw new RuntimeException("Fish node initialization failed: " + error);
        }

        // Load CA certificate
        loadCACertificate();

        // Create gRPC channel
        createGrpcChannel();

        LOGGER.info("Fish node started successfully on " + apiEndpoint);
    }

    /**
     * Stop the Aquarium Fish node
     */
    public void stopFishNode() {
        LOGGER.info("Stopping Aquarium Fish node...");

        isRunning.set(false);

        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }

        if (logReader != null) {
            logReader.shutdown();
        }

        if (fishProcess != null) {
            fishProcess.destroy();
            try {
                if (!fishProcess.waitFor(30, TimeUnit.SECONDS)) {
                    fishProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fishProcess.destroyForcibly();
            }
        }

        // Clean up workspace
        if (workspace != null) {
            try {
                deleteDirectory(workspace.toFile());
            } catch (Exception e) {
                LOGGER.warning("Failed to clean up workspace: " + e.getMessage());
            }
        }

        LOGGER.info("Fish node stopped");
    }

    /**
     * Create a test user for Jenkins authentication
     */
    public void createTestUser() throws Exception {
        LOGGER.info("Creating test user...");

        // First authenticate as admin
        UserOuterClass.UserServiceGetMeRequest meRequest = UserOuterClass.UserServiceGetMeRequest.newBuilder().build();
        UserOuterClass.UserServiceGetMeResponse meResponse = userStub.getMe(meRequest);

        if (!meResponse.getStatus()) {
            throw new RuntimeException("Failed to authenticate as admin: " + meResponse.getMessage());
        }

        LOGGER.info("Authenticated as admin user: " + meResponse.getData().getName());

        // Create test user
        UserOuterClass.User user = UserOuterClass.User.newBuilder()
            .setName("jenkins-user")
            .setPassword("jenkins-password")
            .addRoles("User")
            .build();

        UserOuterClass.UserServiceCreateRequest request = UserOuterClass.UserServiceCreateRequest.newBuilder()
            .setUser(user)
            .build();

        UserOuterClass.UserServiceCreateResponse response = userStub.create(request);

        if (!response.getStatus()) {
            throw new RuntimeException("Failed to create test user: " + response.getMessage());
        }

        LOGGER.info("Test user created successfully");
    }

    /**
     * Create a test label for Jenkins provisioning
     */
    public String createTestLabel() throws Exception {
        LOGGER.info("Creating test label...");

        // Create label definition with proper protobuf structure
        Struct options = Struct.newBuilder()
            .putFields("images", Value.newBuilder()
                .setStringValue("jenkins/inbound-agent:latest")
                .build())
            .build();

        LabelOuterClass.Resources resources = LabelOuterClass.Resources.newBuilder()
            .setCpu(1)
            .setRam(1)
            .setNetwork("nat")
            .build();

        LabelOuterClass.LabelDefinition definition = LabelOuterClass.LabelDefinition.newBuilder()
            .setDriver("docker")
            .setOptions(options)
            .setResources(resources)
            .build();

        LabelOuterClass.Label label = LabelOuterClass.Label.newBuilder()
            .setName("jenkins-test-label")
            .setVersion(1)
            .addDefinitions(definition)
            .build();

        LabelOuterClass.LabelServiceCreateRequest request = LabelOuterClass.LabelServiceCreateRequest.newBuilder()
            .setLabel(label)
            .build();

        LabelOuterClass.LabelServiceCreateResponse response = labelStub.create(request);

        if (!response.getStatus()) {
            throw new RuntimeException("Failed to create test label: " + response.getMessage());
        }

        String labelUid = response.getData().getUid();
        LOGGER.info("Test label created successfully: " + labelUid);
        return labelUid;
    }

    /**
     * Get configuration for Jenkins plugin
     */
    public AquariumCloudConfiguration getPluginConfig(JenkinsRule jenkins) throws IOException {
        // Create jenkins credentials
        String credentialsId = createTestCredentials(jenkins, "jenkins-user", "jenkins-password");
        String certificateId = createTestCertificate(jenkins, getCACertificatePEM());
        return new AquariumCloudConfiguration.Builder()
            .enabled(true)
            .initAddress("https://"+apiEndpoint)
            .credentialsId(credentialsId)
            .certificateId(certificateId)
            .agentConnectionWaitMinutes(1)
            .build();
    }

    public String createTestCredentials(JenkinsRule jenkins, String username, String password) throws IOException {
        CredentialsStore store = lookupStore(jenkins.jenkins);
        store.addCredentials(Domain.global(), new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "aquarium-credentials", null, username, password));
        LOGGER.info("Created test credentials: " + "aquarium-credentials");
        return "aquarium-credentials";
    }

    public String createTestCertificate(JenkinsRule jenkins, byte[] certificate) throws IOException {
        CredentialsStore store = lookupStore(jenkins.jenkins);
        store.addCredentials(Domain.global(),
            new FileCredentialsImpl(CredentialsScope.GLOBAL, "aquarium-certificate", null, "certificate.crt", SecretBytes.fromBytes(certificate)));
        LOGGER.info("Created test certificate: " + "aquarium-certificate");
        return "aquarium-certificate";
    }

    private static CredentialsStore lookupStore(ModelObject object) {
        Iterator<CredentialsStore> stores = CredentialsProvider.lookupStores(object).iterator();
        assertTrue(stores.hasNext());
        CredentialsStore store = stores.next();
        assertEquals("we got the expected store", object, store.getContext());
        return store;
    }

    /**
     * Get the CA certificate as PEM string
     */
    public byte[] getCACertificatePEM() {
        try {
            Path caFile = workspace.resolve("fish_data").resolve("ca.crt");
            return Files.readAllBytes(caFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CA certificate", e);
        }
    }

    /**
     * Get the workspace path
     */
    public Path getWorkspace() {
        return workspace;
    }

    /**
     * Get the API endpoint
     */
    public String getApiEndpoint() {
        return apiEndpoint;
    }

    /**
     * Get the admin token
     */
    public String getAdminToken() {
        return adminToken;
    }

    /**
     * Check if the node is running
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    private String createTestConfig() {
        return "node_location: test_loc\n" +
               "\n" +
               "api_address: 127.0.0.1:0\n" +
               "\n" +
               "drivers:\n" +
               "  gates: {}\n" +
               "  providers:\n" +
               "    docker:\n" +
               "      ignore_non_controlled: true\n";
    }

    private String findFishBinary() throws Exception {
        // Try environment variable first
        String envPath = System.getenv("FISH_PATH");
        if (envPath != null && !envPath.trim().isEmpty()) {
            File file = new File(envPath);
            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        // Try common locations
        String[] commonPaths = {
            "./aquarium-fish",
            "aquarium-fish",
        };

        for (String path : commonPaths) {
            File file = new File(path);
            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        throw new RuntimeException("Could not find aquarium-fish binary. Set FISH_PATH environment variable or ensure binary is in PATH.");
    }

    private void startLogReader() {
        logReader.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fishProcess.getInputStream()))) {

                String line;
                while (isRunning.get() && (line = reader.readLine()) != null) {
                    LOGGER.info("FISH: " + line);

                    // Parse admin token
                    if (line.contains("Admin user pass: ")) {
                        String[] parts = line.split("Admin user pass: ", 2);
                        if (parts.length > 1) {
                            adminToken = parts[1].trim();
                            LOGGER.info("Found admin token: " + adminToken);
                        }
                    }

                    // Parse API endpoint
                    if (line.contains(" server.addr=")) {
                        String[] parts = line.split(" server.addr=", 2);
                        if (parts.length > 1) {
                            apiEndpoint = parts[1].trim();
                            LOGGER.info("Found API endpoint: " + apiEndpoint);
                        }
                    }

                    // Check for initialization completion
                    if (line.contains(" main.fish_init=completed")) {
                        if (adminToken != null && apiEndpoint != null) {
                            initLatch.countDown();
                        }
                    }

                    // Check for errors
                    if (line.contains("ERROR:") || line.contains("FATAL:")) {
                        initError.set(line);
                        initLatch.countDown();
                    }
                }
            } catch (IOException e) {
                if (isRunning.get()) {
                    initError.set("Log reader failed: " + e.getMessage());
                    initLatch.countDown();
                }
            }
        });
    }

    private void loadCACertificate() throws Exception {
        LOGGER.info("Loading CA certificate...");
        Path caFile = workspace.resolve("fish_data").resolve("ca.crt");
        if (!Files.exists(caFile)) {
            throw new RuntimeException("CA certificate file not found: " + caFile);
        }

        try (InputStream is = Files.newInputStream(caFile)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            caCertificate = (X509Certificate) cf.generateCertificate(is);
        }
    }

    private void createGrpcChannel() throws Exception {
        LOGGER.info("Creating gRPC channel with admin authentication...");

        channel = NettyChannelBuilder.forTarget(apiEndpoint)
            .sslContext(GrpcSslContexts.forClient()
                .trustManager(caCertificate)
                .build())
            .intercept(new BasicAuthInterceptor("admin", adminToken))
            .intercept(new PathPrefixInterceptor("grpc/"))
            .build();

        userStub = UserServiceGrpc.newBlockingStub(channel);
        labelStub = LabelServiceGrpc.newBlockingStub(channel);
        applicationStub = ApplicationServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Admin authentication interceptor for gRPC
     */
    private static class BasicAuthInterceptor implements io.grpc.ClientInterceptor {
        private final String authHeader;

        public BasicAuthInterceptor(String username, String password) {
            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            this.authHeader = "Basic " + encoded;
        }

        @Override
        public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                io.grpc.MethodDescriptor<ReqT, RespT> method, io.grpc.CallOptions callOptions, io.grpc.Channel next) {

            return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                @Override
                public void start(io.grpc.ClientCall.Listener<RespT> responseListener, io.grpc.Metadata headers) {
                    // Add admin token header
                    headers.put(io.grpc.Metadata.Key.of("authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER), authHeader);
                    super.start(responseListener, headers);
                }
            };
        }
    }

    private static class PathPrefixInterceptor implements io.grpc.ClientInterceptor {
        private final String prefix;

        public PathPrefixInterceptor(String prefix) {
            this.prefix = prefix; // e.g., "grpc/"
        }

        @Override
        public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                io.grpc.MethodDescriptor<ReqT, RespT> method, io.grpc.CallOptions callOptions, io.grpc.Channel next) {

            // Create a new MethodDescriptor with prefixed fullMethodName
            io.grpc.MethodDescriptor<ReqT, RespT> prefixedMethod = io.grpc.MethodDescriptor.<ReqT, RespT>newBuilder()
                    .setType(method.getType())
                    .setFullMethodName(prefix + method.getFullMethodName())
                    .setRequestMarshaller(method.getRequestMarshaller())
                    .setResponseMarshaller(method.getResponseMarshaller())
                    .setSchemaDescriptor(method.getSchemaDescriptor())
                    .setIdempotent(method.isIdempotent())
                    .setSafe(method.isSafe())
                    .build();

            // Proceed with the prefixed method
            return next.newCall(prefixedMethod, callOptions);
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
}
