/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.smbridge.clients;

import com.amazonaws.greengrass.streammanager.client.StreamManagerClient;
import com.amazonaws.greengrass.streammanager.client.StreamManagerClientFactory;
import com.amazonaws.greengrass.streammanager.client.config.StreamManagerClientConfig;
import com.amazonaws.greengrass.streammanager.client.config.StreamManagerServerInfo;
import com.amazonaws.greengrass.streammanager.client.exception.StreamManagerException;
import com.amazonaws.greengrass.streammanager.model.MessageStreamDefinition;
import com.amazonaws.greengrass.streammanager.model.StrategyOnFull;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.smbridge.StreamDefinition;
import com.aws.greengrass.smbridge.StreamMessage;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

public class SMClient {
    private static final Logger LOGGER = LogManager.getLogger(SMClient.class);

    private StreamManagerClient streamManagerClient;
    @SuppressWarnings("PMD.ImmutableField")
    private AtomicReference<StreamDefinition> streamDefinition = new AtomicReference<>();
    @Getter(AccessLevel.PACKAGE) // Let the unit test inspect this value
    private MessageStreamDefinition defaultStreamDefinition;

    /**
     * Ctr for SMClient.
     *
     * @param  topics            topics passed in by Nucleus
     * @param  port              custom port as parsed from kernel config
     * @param  streamDefinition  stream configs as parsed by SMBridge class
     * @throws SMClientException if unable to create SM Client
     */
    @Inject
    public SMClient(Topics topics, int port, StreamDefinition streamDefinition) throws SMClientException {
        this(topics, streamDefinition, null);
        try {
            final StreamManagerClientConfig config = StreamManagerClientConfig.builder()
                    .serverInfo(StreamManagerServerInfo.builder().port(port).build()).build();
            this.streamManagerClient = StreamManagerClientFactory.standard().withClientConfig(config).build();
        } catch (StreamManagerException e) {
            throw new SMClientException("Unable to create a SM client", e);
        }
        LOGGER.atInfo().kv("port", port).log("Created new Stream Manager client");
    }

    @SuppressWarnings("PMD.UnusedFormalParameter") // topics may be needed later for extensibility with kernel interact
    protected SMClient(Topics topics, StreamDefinition streamDefinition, StreamManagerClient streamManagerClient) {
        this.streamManagerClient = streamManagerClient;
        this.streamDefinition.set(streamDefinition);
    }

    /**
     *  Called after instantiation to set the default stream configuration.
     */
    public void start() {
        for (String key : streamDefinition.get().getStreams().keySet()) {
            if ("default".equalsIgnoreCase(key)) {
                defaultStreamDefinition = streamDefinition.get().getStreams().get(key);
                LOGGER.atDebug("Set default stream configuration");
                return;
            }
        }
        defaultStreamDefinition = new MessageStreamDefinition();
        defaultStreamDefinition.setStrategyOnFull(StrategyOnFull.RejectNewData);
    }

    private Optional<MessageStreamDefinition> findStreamDefinition(String streamName) {
        for (MessageStreamDefinition messageStreamDefinition : streamDefinition.get().getList()) {
            if (messageStreamDefinition.getName().trim().equals(streamName.trim())) {
                return Optional.of(messageStreamDefinition);
            }
        }
        return Optional.empty();
    }

    /**
     * Called by the Message Bridge message handler to publish a message to a given stream.
     *
     * @param  message            encapsulates a stream name and byte-wise payload
     * @throws SMClientException  thrown if encounters an error at any point
     */
    @SuppressWarnings("PMD.AvoidDuplicateLiterals")
    public void publish(StreamMessage message) throws SMClientException {
        try {
            if (!checkStreamExists(message.getStream())) {
                Optional<MessageStreamDefinition> newStream = findStreamDefinition(message.getStream());
                if (!newStream.isPresent()) {
                    newStream = Optional.of(new MessageStreamDefinition(
                            message.getStream(),
                            defaultStreamDefinition.getMaxSize(),
                            defaultStreamDefinition.getStreamSegmentSize(),
                            defaultStreamDefinition.getTimeToLiveMillis(),
                            defaultStreamDefinition.getStrategyOnFull(),
                            defaultStreamDefinition.getPersistence(),
                            defaultStreamDefinition.getFlushOnWrite(),
                            defaultStreamDefinition.getExportDefinition()
                    ));
                }
                streamManagerClient.createMessageStream(newStream.get());
                LOGGER.atInfo().kv("Stream", message.getStream()).log("Created new stream");
                LOGGER.atDebug().kv("Definition", newStream.get().toString()).log("New stream");
            }
        } catch (StreamManagerException e) {
            LOGGER.atError().kv("Stream", message.getStream()).log("Unable to create stream");
            // TODO: Retry
            throw new SMClientException(e.getMessage(), e);
        }

        try {
            streamManagerClient.appendMessage(message.getStream(), message.getPayload());
            LOGGER.atInfo().kv("Stream", message.getStream()).log("Appended message to stream");
        } catch (StreamManagerException e) {
            LOGGER.atError().kv("Stream", message.getStream()).log("Unable to append to stream");
            // TODO: Retry
            throw new SMClientException(e.getMessage(), e);
        }
    }

    private boolean checkStreamExists(String stream) {
        try {
            // Return type: MessageStreamInfo
            streamManagerClient.describeMessageStream(stream);
        } catch (StreamManagerException e) {
            return false;
        }
        return true;
    }
}
