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

import aquarium.v2.*;
import aquarium.v2.UserOuterClass.User;

import com.adobe.ci.aquarium.net.config.AquariumCloudConfiguration;
import com.adobe.ci.aquarium.net.model.Application;
import com.adobe.ci.aquarium.net.model.ApplicationState;
import com.adobe.ci.aquarium.net.model.Label;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.Base64;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.util.UUID;

/**
 * gRPC client for communicating with Aquarium Fish cluster.
 * Handles both streaming and unary RPC calls.
 */
public class AquariumClient {
    private static final Logger LOGGER = Logger.getLogger(AquariumClient.class.getName());

    private final AquariumCloudConfiguration config;
    private ManagedChannel channel;
    private StreamingServiceGrpc.StreamingServiceStub streamingStub;

    // Streaming connections
    private StreamObserver<Streaming.StreamingServiceConnectRequest> connectStream;
    private StreamObserver<Streaming.StreamingServiceSubscribeRequest> subscribeStream;
    private volatile boolean connected = false;

    // Connection establishment tracking
    private volatile boolean connectStreamEstablished = false;
    private volatile boolean subscribeStreamEstablished = false;
    private volatile Throwable connectionError = null;
    private final Object connectionLock = new Object();

    // Request tracking for bidirectional streaming
    private final Map<String, CompletableFuture<Streaming.StreamingServiceConnectResponse>> pendingRequests = new ConcurrentHashMap<>();

    // Reconnection handling
    private volatile boolean reconnect = false;
    private final ScheduledExecutorService reconnectionScheduler = Executors.newScheduledThreadPool(1);
    private volatile boolean reconnectionScheduled = false;

    // Listeners
    private final List<ApplicationStateListener> stateListeners = new ArrayList<>();
    private final List<LabelChangeListener> labelListeners = new ArrayList<>();
    private final List<ConnectionStatusListener> connectionListeners = new ArrayList<>();

    public interface ApplicationStateListener {
        void onStateChange(ApplicationState state);
    }

    public interface LabelChangeListener {
        void onLabelCreated(Label label);
        void onLabelUpdated(Label label);
        void onLabelRemoved(String labelUid);
    }

    public interface ConnectionStatusListener {
        void onConnectionStatusChanged(boolean connected);
    }

    public AquariumClient(AquariumCloudConfiguration config, boolean reconnect) {
        this.config = config;
        this.reconnect = reconnect;
    }

    /**
     * Connect to the Aquarium Fish cluster
     */
    public void connect() throws Exception {
        if (connected) {
            return;
        }

        String[] hostParts = config.getInitAddress().split(":");
        String host = hostParts[0];
        int port = hostParts.length > 1 ? Integer.parseInt(hostParts[1]) : 8001;

        NettyChannelBuilder channelBuilder = NettyChannelBuilder
                .forAddress(host, port)
                .intercept(new PathPrefixInterceptor("grpc/"))
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .maxInboundMessageSize(4 * 1024 * 1024); // 4MB

        // Add basic auth if credentials are provided
        if (config.getUsername() != null && config.getPasswordPlainText() != null) {
            channelBuilder.intercept(new BasicAuthInterceptor(config.getUsername(), config.getPasswordPlainText()));
        }

        // Add certificate verification if provided
        if (config.getCertificate() != null && !config.getCertificate().trim().isEmpty()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(config.getCertificate().getBytes()));
            channelBuilder.sslContext(GrpcSslContexts.forClient()
                .trustManager(cert)
                .build());
        } else {
            channelBuilder.usePlaintext();
        }

        this.channel = channelBuilder.build();

        // Create streaming stub
        this.streamingStub = StreamingServiceGrpc.newStub(channel);

        // Reset connection tracking
        connectStreamEstablished = false;
        subscribeStreamEstablished = false;
        connectionError = null;

        // Establish streaming connections
        establishStreams();

        // Wait for both streams to be established or for an error to occur
        synchronized (connectionLock) {
            long startTime = System.currentTimeMillis();
            long timeout = 30000; // 30 seconds timeout

            while (!connectStreamEstablished || !subscribeStreamEstablished) {
                if (connectionError != null) {
                    // An error occurred during stream establishment
                    throw new RuntimeException("Failed to establish streaming connections: " + connectionError.toString(), connectionError);
                }

                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= timeout) {
                    throw new RuntimeException("Timeout waiting for streaming connections to be established");
                }

                try {
                    connectionLock.wait(1000); // Wait 1 second at a time
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Connection establishment was interrupted", e);
                }
            }
        }

        this.connected = true;
        notifyConnectionStatusChanged(true);
        LOGGER.info("Successfully connected to Aquarium Fish at " + config.getInitAddress());
    }

    /**
     * Establish streaming connections for Connect and Subscribe
     */
    private void establishStreams() {
        // Connect stream for bidirectional RPC communication
        connectStream = streamingStub.connect(new StreamObserver<Streaming.StreamingServiceConnectResponse>() {
            @Override
            public void onNext(Streaming.StreamingServiceConnectResponse response) {
                // Mark streams as established if no immediate errors occurred
                synchronized (connectionLock) {
                    if (!connectStreamEstablished) {
                        connectStreamEstablished = true;
                    }
                }
                handleConnectResponse(response);
            }

            @Override
            public void onError(Throwable t) {
                LOGGER.log(Level.WARNING, "Connect stream error", t);
                synchronized (connectionLock) {
                    connectionError = t;
                    connectStreamEstablished = false;
                    connectionLock.notifyAll();
                }
                connected = false;
                notifyConnectionStatusChanged(false);
                scheduleReconnection();
            }

            @Override
            public void onCompleted() {
                LOGGER.info("Connect stream completed");
                synchronized (connectionLock) {
                    connectStreamEstablished = false;
                    connectionLock.notifyAll();
                }
                connected = false;
                notifyConnectionStatusChanged(false);
                scheduleReconnection();
            }
        });

        // Subscribe stream for database change notifications
        StreamObserver<Streaming.StreamingServiceSubscribeResponse> subscribeResponseObserver =
                new StreamObserver<Streaming.StreamingServiceSubscribeResponse>() {
            @Override
            public void onNext(Streaming.StreamingServiceSubscribeResponse response) {
                // Mark streams as established if no immediate errors occurred
                synchronized (connectionLock) {
                    if (!subscribeStreamEstablished) {
                        subscribeStreamEstablished = true;
                    }
                }
                handleSubscribeResponse(response);
            }

            @Override
            public void onError(Throwable t) {
                LOGGER.log(Level.WARNING, "Subscribe stream error", t);
                synchronized (connectionLock) {
                    connectionError = t;
                    subscribeStreamEstablished = false;
                    connectionLock.notifyAll();
                }
                connected = false;
                notifyConnectionStatusChanged(false);
                scheduleReconnection();
            }

            @Override
            public void onCompleted() {
                LOGGER.info("Subscribe stream completed");
                synchronized (connectionLock) {
                    subscribeStreamEstablished = false;
                    connectionLock.notifyAll();
                }
                connected = false;
                notifyConnectionStatusChanged(false);
                scheduleReconnection();
            }
        };

        // Send initial subscribe request for application states and labels
        Streaming.StreamingServiceSubscribeRequest subscribeRequest = Streaming.StreamingServiceSubscribeRequest.newBuilder()
                .addSubscriptionTypes(Streaming.SubscriptionType.SUBSCRIPTION_TYPE_APPLICATION_STATE)
                .addSubscriptionTypes(Streaming.SubscriptionType.SUBSCRIPTION_TYPE_LABEL)
                .build();

        try {
            streamingStub.subscribe(subscribeRequest, subscribeResponseObserver);
        } catch (Exception e) {
            // If subscribe request fails immediately, mark as error
            synchronized (connectionLock) {
                connectionError = e;
                subscribeStreamEstablished = false;
                connectionLock.notifyAll();
            }
            throw e;
        }
    }

    /**
     * Schedule reconnection attempt after 10 seconds
     */
    private void scheduleReconnection() {
        if(!reconnect) {
            return;
        }

        if (!reconnectionScheduled) {
            reconnectionScheduled = true;
            reconnectionScheduler.schedule(() -> {
                try {
                    LOGGER.info("Attempting to reconnect to Aquarium Fish...");
                    reconnect();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Reconnection attempt failed", e);
                    scheduleReconnection(); // Schedule another attempt
                } finally {
                    reconnectionScheduled = false;
                }
            }, 10, TimeUnit.SECONDS);
        }
    }

    /**
     * Attempt to reconnect to the cluster
     */
    private void reconnect() throws Exception {
        disconnect();
        connect();
        // Connection status will be notified in connect() method
    }

    /**
     * Handle responses from Connect stream
     */
    private void handleConnectResponse(Streaming.StreamingServiceConnectResponse response) {
        String requestId = response.getRequestId();
        LOGGER.fine("Received connect response for request: " + requestId);

        // Find the pending request and complete it
        CompletableFuture<Streaming.StreamingServiceConnectResponse> pendingRequest = pendingRequests.remove(requestId);
        if (pendingRequest != null) {
            if (response.hasError()) {
                // Complete with exception if there's an error
                Exception error = new RuntimeException("Stream request failed: " + response.getError().getMessage());
                pendingRequest.completeExceptionally(error);
            } else {
                // Complete successfully with the response
                pendingRequest.complete(response);
            }
        } else if (requestId.equals("keep-alive")) {
            LOGGER.fine("Received keep-alive response");
        } else {
            LOGGER.warning("Received response for unknown request ID: " + requestId);
        }
    }

    /**
     * Handle responses from Subscribe stream
     */
    private void handleSubscribeResponse(Streaming.StreamingServiceSubscribeResponse response) {
        if (response.getObjectType() == Streaming.SubscriptionType.SUBSCRIPTION_TYPE_APPLICATION_STATE) {
            // Handle application state changes
            try {
                ApplicationOuterClass.ApplicationState state = response.getObjectData().unpack(ApplicationOuterClass.ApplicationState.class);
                notifyApplicationStateChange(new ApplicationState(state));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to handle application state change", e);
            }
        } else if (response.getObjectType() == Streaming.SubscriptionType.SUBSCRIPTION_TYPE_LABEL) {
            // Handle label changes
            try {
                LabelOuterClass.Label labelProto = response.getObjectData().unpack(LabelOuterClass.Label.class);
                Label label = new Label(labelProto);

                switch (response.getChangeType()) {
                    case CHANGE_TYPE_CREATED:
                        notifyLabelCreated(label);
                        break;
                    case CHANGE_TYPE_UPDATED:
                        notifyLabelUpdated(label);
                        break;
                    case CHANGE_TYPE_REMOVED:
                        notifyLabelRemoved(label.getUid());
                        break;
                    default:
                        LOGGER.fine("Unknown label change type: " + response.getChangeType());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to handle label change", e);
            }
        }
    }

    /**
     * Send a request through the Connect stream and wait for response
     */
    private Streaming.StreamingServiceConnectResponse sendStreamRequest(String requestType, com.google.protobuf.Any requestData) throws Exception {
        if (!connected || connectStream == null) {
            throw new IllegalStateException("Client is not connected");
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Streaming.StreamingServiceConnectResponse> responseFuture = new CompletableFuture<>();

        // Register the pending request
        pendingRequests.put(requestId, responseFuture);

        try {
            // Send the request through the stream
            Streaming.StreamingServiceConnectRequest streamRequest = Streaming.StreamingServiceConnectRequest.newBuilder()
                .setRequestId(requestId)
                .setRequestType(requestType)
                .setRequestData(requestData)
                .build();

            connectStream.onNext(streamRequest);

            // Wait for response with timeout
            return responseFuture.get(30, TimeUnit.SECONDS);

        } catch (Exception e) {
            // Clean up on failure
            pendingRequests.remove(requestId);
            throw e;
        }
    }

    /**
     * Test connection by calling UserService.GetMe
     */
    public User getMe() throws Exception {
        UserOuterClass.UserServiceGetMeRequest request = UserOuterClass.UserServiceGetMeRequest.newBuilder().build();

        Streaming.StreamingServiceConnectResponse response = sendStreamRequest("UserServiceGetMeRequest",
            com.google.protobuf.Any.pack(request));

        UserOuterClass.UserServiceGetMeResponse userResponse = response.getResponseData()
            .unpack(UserOuterClass.UserServiceGetMeResponse.class);

        return userResponse.getData();
    }

    /**
     * List all available labels via streaming
     */
    public List<Label> listLabels() throws Exception {
        LabelOuterClass.LabelServiceListRequest request = LabelOuterClass.LabelServiceListRequest.newBuilder().build();

        Streaming.StreamingServiceConnectResponse response = sendStreamRequest("LabelServiceListRequest",
            com.google.protobuf.Any.pack(request));

        LabelOuterClass.LabelServiceListResponse labelResponse = response.getResponseData()
            .unpack(LabelOuterClass.LabelServiceListResponse.class);

        if (!labelResponse.getStatus()) {
            throw new RuntimeException("Failed to list labels: " + labelResponse.getMessage());
        }

        List<Label> labels = new ArrayList<>();
        for (LabelOuterClass.Label protoLabel : labelResponse.getDataList()) {
            labels.add(new Label(protoLabel));
        }

        return labels;
    }

    /**
     * Create a new application via streaming
     */
    public Application createApplication(ApplicationOuterClass.Application applicationProto) throws Exception {
        ApplicationOuterClass.ApplicationServiceCreateRequest request = ApplicationOuterClass.ApplicationServiceCreateRequest.newBuilder()
                .setApplication(applicationProto)
                .build();

        Streaming.StreamingServiceConnectResponse response = sendStreamRequest("ApplicationServiceCreateRequest",
            com.google.protobuf.Any.pack(request));

        ApplicationOuterClass.ApplicationServiceCreateResponse appResponse = response.getResponseData()
            .unpack(ApplicationOuterClass.ApplicationServiceCreateResponse.class);

        if (!appResponse.getStatus()) {
            throw new RuntimeException("Failed to create application: " + appResponse.getMessage());
        }

        return new Application(appResponse.getData());
    }

    /**
     * Deallocate an application via streaming
     */
    public void deallocateApplication(String applicationUid) throws Exception {
        ApplicationOuterClass.ApplicationServiceDeallocateRequest request = ApplicationOuterClass.ApplicationServiceDeallocateRequest.newBuilder()
                .setApplicationUid(applicationUid)
                .build();

        Streaming.StreamingServiceConnectResponse response = sendStreamRequest("ApplicationServiceDeallocateRequest",
            com.google.protobuf.Any.pack(request));

        ApplicationOuterClass.ApplicationServiceDeallocateResponse deallocateResponse = response.getResponseData()
            .unpack(ApplicationOuterClass.ApplicationServiceDeallocateResponse.class);

        if (!deallocateResponse.getStatus()) {
            LOGGER.warning("Failed to deallocate application " + applicationUid + ": " + deallocateResponse.getMessage());
        } else {
            LOGGER.info("Successfully deallocated application " + applicationUid);
        }
    }

    /**
     * Get application state - handled via Subscribe stream monitoring
     * This method is deprecated in favor of real-time state monitoring
     */
    @Deprecated
    public ApplicationState applicationStateGet(java.util.UUID applicationUid) throws Exception {
        ApplicationOuterClass.ApplicationServiceGetStateRequest request = ApplicationOuterClass.ApplicationServiceGetStateRequest.newBuilder()
                .setApplicationUid(applicationUid.toString())
                .build();

        Streaming.StreamingServiceConnectResponse response = sendStreamRequest("ApplicationServiceGetTaskRequest",
            com.google.protobuf.Any.pack(request));

        ApplicationOuterClass.ApplicationServiceGetStateResponse stateResponse = response.getResponseData()
            .unpack(ApplicationOuterClass.ApplicationServiceGetStateResponse.class);

        if (!stateResponse.getStatus()) {
            throw new RuntimeException("Failed to get application state: " + stateResponse.getMessage());
        }

        return new ApplicationState(stateResponse.getData());
    }

    /**
     * Get application task via streaming
     */
    public ApplicationOuterClass.ApplicationTask taskGet(java.util.UUID taskUid) throws Exception {
        ApplicationOuterClass.ApplicationServiceGetTaskRequest request = ApplicationOuterClass.ApplicationServiceGetTaskRequest.newBuilder()
                .setApplicationTaskUid(taskUid.toString())
                .build();

        Streaming.StreamingServiceConnectResponse response = sendStreamRequest("ApplicationServiceGetTaskRequest",
            com.google.protobuf.Any.pack(request));

        ApplicationOuterClass.ApplicationServiceGetTaskResponse taskResponse = response.getResponseData()
            .unpack(ApplicationOuterClass.ApplicationServiceGetTaskResponse.class);

        if (!taskResponse.getStatus()) {
            throw new RuntimeException("Failed to get application task: " + taskResponse.getMessage());
        }

        return taskResponse.getData();
    }

    /**
     * Create application task for snapshot via streaming
     */
    public String applicationTaskSnapshot(java.util.UUID applicationUid, ApplicationOuterClass.ApplicationState.Status when, boolean full) throws Exception {
        // Create an ApplicationTask for snapshot operation
        ApplicationOuterClass.ApplicationTask.Builder taskBuilder = ApplicationOuterClass.ApplicationTask.newBuilder()
                .setApplicationUid(applicationUid.toString())
                .setTask("snapshot")  // Task type
                .setWhen(when);

        // Add full parameter to options
        com.google.protobuf.Struct.Builder optionsBuilder = com.google.protobuf.Struct.newBuilder();
        optionsBuilder.putFields("full", com.google.protobuf.Value.newBuilder().setBoolValue(full).build());
        taskBuilder.setOptions(optionsBuilder.build());

        ApplicationOuterClass.ApplicationServiceCreateTaskRequest request = ApplicationOuterClass.ApplicationServiceCreateTaskRequest.newBuilder()
                .setTask(taskBuilder.build())
                .build();

        Streaming.StreamingServiceConnectResponse response = sendStreamRequest("ApplicationServiceCreateTaskRequest",
            com.google.protobuf.Any.pack(request));

        ApplicationOuterClass.ApplicationServiceCreateTaskResponse taskResponse = response.getResponseData()
            .unpack(ApplicationOuterClass.ApplicationServiceCreateTaskResponse.class);

        if (!taskResponse.getStatus()) {
            throw new RuntimeException("Failed to create snapshot task: " + taskResponse.getMessage());
        }

        return taskResponse.getData().getUid();
    }

    /**
     * Create application task for image creation via streaming
     */
    public String applicationTaskImage(java.util.UUID applicationUid, ApplicationOuterClass.ApplicationState.Status when, boolean full) throws Exception {
        // Create an ApplicationTask for image operation
        ApplicationOuterClass.ApplicationTask.Builder taskBuilder = ApplicationOuterClass.ApplicationTask.newBuilder()
                .setApplicationUid(applicationUid.toString())
                .setTask("image")  // Task type
                .setWhen(when);

        // Add full parameter to options
        com.google.protobuf.Struct.Builder optionsBuilder = com.google.protobuf.Struct.newBuilder();
        optionsBuilder.putFields("full", com.google.protobuf.Value.newBuilder().setBoolValue(full).build());
        taskBuilder.setOptions(optionsBuilder.build());

        ApplicationOuterClass.ApplicationServiceCreateTaskRequest request = ApplicationOuterClass.ApplicationServiceCreateTaskRequest.newBuilder()
                .setTask(taskBuilder.build())
                .build();

        Streaming.StreamingServiceConnectResponse response = sendStreamRequest("ApplicationServiceCreateTaskRequest",
            com.google.protobuf.Any.pack(request));

        ApplicationOuterClass.ApplicationServiceCreateTaskResponse taskResponse = response.getResponseData()
            .unpack(ApplicationOuterClass.ApplicationServiceCreateTaskResponse.class);

        if (!taskResponse.getStatus()) {
            throw new RuntimeException("Failed to create image task: " + taskResponse.getMessage());
        }

        return taskResponse.getData().getUid();
    }

    // Application state monitoring
    public void monitorApplicationState(String applicationUid, Consumer<ApplicationState> callback) {
        stateListeners.add(new ApplicationStateListener() {
            @Override
            public void onStateChange(ApplicationState state) {
                if (applicationUid.equals(state.getApplicationUid())) {
                    callback.accept(state);
                }
            }
        });
    }

    private void notifyApplicationStateChange(ApplicationState state) {
        for (ApplicationStateListener listener : stateListeners) {
            try {
                listener.onStateChange(state);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying state listener", e);
            }
        }
    }

    // Label change monitoring
    public void addLabelChangeListener(LabelChangeListener listener) {
        labelListeners.add(listener);
    }

    public void removeLabelChangeListener(LabelChangeListener listener) {
        labelListeners.remove(listener);
    }

    private void notifyLabelCreated(Label label) {
        for (LabelChangeListener listener : labelListeners) {
            try {
                listener.onLabelCreated(label);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying label created listener", e);
            }
        }
    }

    private void notifyLabelUpdated(Label label) {
        for (LabelChangeListener listener : labelListeners) {
            try {
                listener.onLabelUpdated(label);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying label updated listener", e);
            }
        }
    }

    private void notifyLabelRemoved(String labelUid) {
        for (LabelChangeListener listener : labelListeners) {
            try {
                listener.onLabelRemoved(labelUid);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying label removed listener", e);
            }
        }
    }

    // Connection status monitoring
    public void addConnectionStatusListener(ConnectionStatusListener listener) {
        connectionListeners.add(listener);
    }

    public void removeConnectionStatusListener(ConnectionStatusListener listener) {
        connectionListeners.remove(listener);
    }

    private void notifyConnectionStatusChanged(boolean connected) {
        for (ConnectionStatusListener listener : connectionListeners) {
            try {
                listener.onConnectionStatusChanged(connected);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying connection status listener", e);
            }
        }
    }

    /**
     * Disconnect from the cluster
     */
    public void disconnect() {
        try {
            connected = false;
            connectStreamEstablished = false;
            subscribeStreamEstablished = false;
            connectionError = null;
            notifyConnectionStatusChanged(false);

            if (connectStream != null) {
                connectStream.onCompleted();
            }

            if (subscribeStream != null) {
                subscribeStream.onCompleted();
            }

            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            }

            LOGGER.info("Disconnected from Aquarium Fish");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during disconnect", e);
        }
    }

    public boolean isConnected() {
        return connected && channel != null && !channel.isShutdown();
    }

    /**
     * Shutdown the client and cleanup resources
     */
    public void shutdown() {
        if (reconnectionScheduler != null && !reconnectionScheduler.isShutdown()) {
            reconnectionScheduler.shutdown();
        }
        disconnect();
    }

    /**
     * Path prefix interceptor allows gRPC to work with http server on path
     */
    private static class PathPrefixInterceptor implements io.grpc.ClientInterceptor {
        private final String prefix;

        public PathPrefixInterceptor(String prefix) {
            this.prefix = prefix; // e.g., "grpc/"
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

            // Create a new MethodDescriptor with prefixed fullMethodName
            MethodDescriptor<ReqT, RespT> prefixedMethod = MethodDescriptor.<ReqT, RespT>newBuilder()
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

    /**
     * Basic authentication interceptor for gRPC
     */
    private static class BasicAuthInterceptor implements ClientInterceptor {
        private final String authHeader;

        public BasicAuthInterceptor(String username, String password) {
            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            this.authHeader = "Basic " + encoded;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    // Add basic auth header
                    headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), authHeader);
                    super.start(responseListener, headers);
                }
            };
        }
    }
}
