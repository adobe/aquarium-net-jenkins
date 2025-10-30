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

package com.adobe.ci.aquarium.net.integration;

import aquarium.v2.*;
import com.adobe.ci.aquarium.net.config.AquariumCloudConfiguration;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.ListValue;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.rules.ExternalResource;
import org.jvnet.hudson.test.JenkinsRule;
import hudson.model.ModelObject;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.SecretBytes;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.CredentialsProvider;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
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
    private RoleServiceGrpc.RoleServiceBlockingStub roleStub;
    private LabelServiceGrpc.LabelServiceBlockingStub labelStub;
    private ApplicationServiceGrpc.ApplicationServiceBlockingStub applicationStub;
    private NodeServiceGrpc.NodeServiceBlockingStub nodeStub;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ExecutorService logReader = Executors.newSingleThreadExecutor();
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private final AtomicReference<String> initError = new AtomicReference<>();

    // TCP Proxy fields
    private TCPProxy tcpProxy;
    private URL proxyUrl;
    private ExecutorService proxyExecutor;

    @Override
    protected void after() {
        stopTcpProxy();
        stopFishNode();
    }

    /**
     * Start the Aquarium Fish node with test configuration
     */
    public void startFishNode(String config) throws Exception {
        LOGGER.info("Starting Aquarium Fish node...");

        // Create workspace
        workspace = Files.createTempDirectory("fish-test-");
        LOGGER.info("Created workspace: " + workspace);

        // Create configuration file
        if (config == null) {
            config = createTestConfig();
        }
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
        fishLogReader();

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

        try {
            // Sending maintenance request to gracefully stop the node
            sendMaintenanceRequest(true, true);
            LOGGER.info("Sent maintenance request, waiting for 5 seconds...");
            Thread.sleep(5000); // Wait for 5 seconds to ensure the node is stopped
        } catch (Exception e) {
            LOGGER.warning("Failed to send maintenance request: " + e.getMessage());
        }

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
    public void sendMaintenanceRequest(boolean maintenance, boolean shutdown) throws Exception {
        LOGGER.info("Sending maintenance request...");

        NodeOuterClass.NodeServiceSetMaintenanceRequest request = NodeOuterClass.NodeServiceSetMaintenanceRequest.newBuilder()
            .setMaintenance(maintenance)
            .setShutdown(shutdown)
            .setShutdownDelay("2s")
            .build();

            NodeOuterClass.NodeServiceSetMaintenanceResponse response = nodeStub.setMaintenance(request);

        if (!response.getStatus()) {
            throw new RuntimeException("Failed to send maintenance request: " + response.getMessage());
        }

        LOGGER.info("Maintenance request sent successfully");
    }

    /**
     * Create a simple test user with User role for Jenkins authentication
     */
    public void createSimpleTestUser() throws Exception {
        LOGGER.info("Creating simple test user...");

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

        LOGGER.info("Simple test user created successfully");
    }

    /**
     * Create an advanced test user with custom role for special Jenkins authentication
     */
    public void createAdvancedTestUser() throws Exception {
        LOGGER.info("Creating advanced test user...");

        // Create custom role for jenkins to cover advanced usecases
        RoleOuterClass.Role role = RoleOuterClass.Role.newBuilder()
            .setName("Jenkins")
            // User - ApplicationService
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("Create").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("Deallocate").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("Get").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("GetResource").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("GetState").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("List").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("ListResource").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("ListState").build()
            )
            // User - LabelService
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("LabelService").setAction("List").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("LabelService").setAction("Get").build()
            )
            // User - StreamingService
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("StreamingService").setAction("Connect").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("StreamingService").setAction("Subscribe").build()
            )
            // Power - ApplicationService - is needed to create tasks (create snasphots/images)
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("CreateTask").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("GetTask").build()
            )
            .addPermissions(
                RoleOuterClass.Permission.newBuilder()
                    .setResource("ApplicationService").setAction("ListTask").build()
            )
            .build();

        RoleOuterClass.RoleServiceCreateRequest request1 = RoleOuterClass.RoleServiceCreateRequest.newBuilder()
            .setRole(role)
            .build();

        RoleOuterClass.RoleServiceCreateResponse response1 = roleStub.create(request1);

        if (!response1.getStatus()) {
            throw new RuntimeException("Failed to create Jenkins role: " + response1.getData());
        }

        LOGGER.info("Jenkins role created successfully");

        // Create test user
        UserOuterClass.User user = UserOuterClass.User.newBuilder()
            .setName("jenkins-user")
            .setPassword("jenkins-password")
            .addRoles("Jenkins")
            .build();

        UserOuterClass.UserServiceCreateRequest request2 = UserOuterClass.UserServiceCreateRequest.newBuilder()
            .setUser(user)
            .build();

        UserOuterClass.UserServiceCreateResponse response2 = userStub.create(request2);

        if (!response2.getStatus()) {
            throw new RuntimeException("Failed to create test user: " + response2.getMessage());
        }

        LOGGER.info("Advanced test user created successfully");
    }

    /**
     * Create a test label for Jenkins provisioning
     */
    public String createTestLabel() throws Exception {
        LOGGER.info("Creating test label...");

        LabelOuterClass.Resources resources = LabelOuterClass.Resources.newBuilder()
            .setCpu(1)
            .setRam(1)
            .setNetwork("nat")
            .build();

        LabelOuterClass.LabelDefinition definition = LabelOuterClass.LabelDefinition.newBuilder()
            .setDriver("docker")
            .addImages(LabelOuterClass.Image.newBuilder()
                .setName("jenkins-agent-docker")
                .setVersion("java11")
                .setUrl("http://predefined/image"))
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
        String rootUrl = jenkins.getInstance().getRootUrl();
        if (rootUrl == null) {
            throw new IOException("Jenkins root URL is null");
        }

        // Start TCP proxy to allow Docker containers to connect to JenkinsRule which is listening on localhost
        URL url = new URL(rootUrl);
        URL newurl = startTcpProxy(url);

        // Create jenkins credentials
        String credentialsId = createTestCredentials(jenkins, "jenkins-user", "jenkins-password");
        String certificateId = createTestCertificate(jenkins, getCACertificatePEM());

        return new AquariumCloudConfiguration.Builder()
            .enabled(true)
            .initAddress("https://"+apiEndpoint)
            .jenkinsUrl(newurl.toExternalForm())
            .credentialsId(credentialsId)
            .certificateId(certificateId)
            .agentConnectionWaitMinutes(1)
            .build();
    }

    /**
     * Start TCP proxy to forward traffic from 0.0.0.0 to localhost Jenkins
     */
    private URL startTcpProxy(URL url) throws IOException {
        if (tcpProxy != null) {
            return proxyUrl; // Already started
        }

        // Extract port from Jenkins root URL
        int port = url.getPort();
        if (port == -1) {
            port = url.getDefaultPort(); // 80 for http, 443 for https
        }

        try {
            int targetPort = port+20;
            LOGGER.info("Starting TCP proxy from 0.0.0.0:" + targetPort + " to localhost:" + port);

            // Create and start TCP proxy
            tcpProxy = new TCPProxy(port, targetPort);
            proxyExecutor = Executors.newCachedThreadPool();

            proxyExecutor.submit(() -> {
                try {
                    tcpProxy.start();
                } catch (IOException e) {
                    LOGGER.severe("Failed to start TCP proxy: " + e.getMessage());
                    throw new RuntimeException("Failed to start TCP proxy", e);
                }
            });

            // Give the proxy a moment to start
            Thread.sleep(1000);

            proxyUrl = new URL(url.getProtocol(), "host.docker.internal", targetPort, url.getFile());
            return proxyUrl;
        } catch (Exception e) {
            throw new IOException("Failed to start TCP proxy", e);
        }
    }

    /**
     * Stop TCP proxy
     */
    private void stopTcpProxy() {
        LOGGER.info("Stopping TCP proxy...");

        if (tcpProxy != null) {
            tcpProxy.stop();
            tcpProxy = null;
        }

        if (proxyExecutor != null) {
            proxyExecutor.shutdown();
            try {
                if (!proxyExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    proxyExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                proxyExecutor.shutdownNow();
            }
            proxyExecutor = null;
        }

        LOGGER.info("TCP proxy stopped");
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

    private void fishLogReader() {
        logReader.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fishProcess.getInputStream()))) {

                String line;
                while (isRunning.get() && (line = reader.readLine()) != null) {
                    LOGGER.finer("FISH: " + line);
                    // Debug logic help to understand what's up with the docker container before deallocation
                    //if (line.contains("rpc.req_type=ApplicationServiceDeallocateRequest")) {
                    //    ProcessBuilder processBuilder = new ProcessBuilder();
                    //    processBuilder.command("sh", "-c", "for i in $(docker ps -q); do docker logs $i; done");
                    //    processBuilder.redirectErrorStream(true);
                    //    Process process = processBuilder.start();
                    //
                    //    BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    //    String l;
                    //    while ((l = r.readLine()) != null) {
                    //        LOGGER.fine("DOCKER: " + l);
                    //    }
                    //}

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
        roleStub = RoleServiceGrpc.newBlockingStub(channel);
        labelStub = LabelServiceGrpc.newBlockingStub(channel);
        applicationStub = ApplicationServiceGrpc.newBlockingStub(channel);
        nodeStub = NodeServiceGrpc.newBlockingStub(channel);
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

    /**
     * Simple TCP proxy to forward traffic from 0.0.0.0 to JenkinsRule on localhost
     * This allows Docker containers to connect to Jenkins running on localhost
     */
    private static class TCPProxy {
        private final int localPort;
        private final int targetPort;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private ServerSocket serverSocket;
        private final ExecutorService connectionHandlers = Executors.newCachedThreadPool();

        public TCPProxy(int localPort, int targetPort) {
            this.localPort = localPort;
            this.targetPort = targetPort;
        }

        public void start() throws IOException {
            if (running.get()) {
                return;
            }

            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", targetPort));
            running.set(true);

            LOGGER.info("TCP Proxy started on 0.0.0.0:" + targetPort + " -> localhost:" + localPort);

            // Accept connections in a separate thread
            connectionHandlers.submit(() -> {
                while (running.get() && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        connectionHandlers.submit(new ConnectionHandler(clientSocket, localPort));
                    } catch (IOException e) {
                        if (running.get()) {
                            LOGGER.warning("Error accepting connection: " + e.getMessage());
                        }
                    }
                }
            });
        }

        public void stop() {
            running.set(false);

            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    LOGGER.warning("Error closing server socket: " + e.getMessage());
                }
            }

            connectionHandlers.shutdown();
            try {
                if (!connectionHandlers.awaitTermination(5, TimeUnit.SECONDS)) {
                    connectionHandlers.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                connectionHandlers.shutdownNow();
            }

            LOGGER.info("TCP Proxy stopped");
        }

        private static class ConnectionHandler implements Runnable {
            private final Socket clientSocket;
            private final int targetPort;

            public ConnectionHandler(Socket clientSocket, int targetPort) {
                this.clientSocket = clientSocket;
                this.targetPort = targetPort;
            }

            @Override
            public void run() {
                try (Socket targetSocket = new Socket("localhost", targetPort)) {
                    LOGGER.fine("Proxying connection from " + clientSocket.getRemoteSocketAddress() +
                               " to localhost:" + targetPort);

                    // Create bidirectional data forwarding threads
                    Thread clientToTarget = new Thread(() -> {
                        try {
                            forwardData(clientSocket.getInputStream(), targetSocket.getOutputStream());
                        } catch (IOException e) {
                            LOGGER.fine("Client to target forwarding ended: " + e.getMessage());
                        }
                    });

                    Thread targetToClient = new Thread(() -> {
                        try {
                            forwardData(targetSocket.getInputStream(), clientSocket.getOutputStream());
                        } catch (IOException e) {
                            LOGGER.fine("Target to client forwarding ended: " + e.getMessage());
                        }
                    });

                    clientToTarget.start();
                    targetToClient.start();

                    // Wait for either thread to finish (connection closed)
                    clientToTarget.join();
                    targetToClient.join();

                } catch (IOException e) {
                    LOGGER.fine("Error connecting to target: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    try {
                        clientSocket.close();
                    } catch (IOException e) {
                        LOGGER.fine("Error closing client socket: " + e.getMessage());
                    }
                }
            }

            private void forwardData(InputStream input, OutputStream output) throws IOException {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    output.flush();
                }
            }
        }
    }
}
