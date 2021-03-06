/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.smbridge;

import com.amazonaws.greengrass.streammanager.model.MessageStreamDefinition;
import com.aws.greengrass.certificatemanager.certificate.CsrProcessingException;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.device.ClientDevicesAuthService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.PluginService;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.smbridge.auth.CsrGeneratingException;
import com.aws.greengrass.smbridge.auth.MQTTClientKeyStore;
import com.aws.greengrass.smbridge.clients.MQTTClient;
import com.aws.greengrass.smbridge.clients.MQTTClientException;
import com.aws.greengrass.smbridge.clients.SMClient;
import com.aws.greengrass.smbridge.clients.SMClientException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.AccessLevel;
import lombok.Getter;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;

@ImplementsService(name = SMBridge.SERVICE_NAME)
public class SMBridge extends PluginService {
    public static final String SERVICE_NAME = "aws.greengrass.StreamManagerBridge";

    @Getter(AccessLevel.PACKAGE) // Getter for unit tests
    private final TopicMapping topicMapping;
    private final StreamDefinition streamDefinition;

    private final MessageBridge messageBridge;
    private final Kernel kernel;
    private final MQTTClientKeyStore mqttClientKeyStore;
    private final ExecutorService executorService;
    private MQTTClient mqttClient;
    private SMClient smClient;
    private static final JsonMapper OBJECT_MAPPER =
            JsonMapper.builder().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES).build();
    static final String MQTT_STREAM_MAPPING = "mqttStreamMapping";
    static final String STREAM_DEFINITION = "streamDefinition";
    static final String STREAM_MANAGER_PORT_KEY = "STREAM_MANAGER_SERVER_PORT";
    static final String RESERVED_TOPIC = "$SM-BRIDGE/+/#";
    static boolean SINGLE_DEFAULT_STREAM = true;
    static boolean APPEND_TIME_DEFAULT_STREAM = true;
    static boolean APPEND_TOPIC_DEFAULT_STREAM = true;
    private Topics mappingConfigTopics;
    private Topics streamsConfigTopics;

    /**
     * Ctr for SMBridge.
     *
     * @param topics             topics passed by by the Nucleus
     * @param topicMapping       mapping of mqtt topics to iotCore/pubsub topics
     * @param streamDefinition   definition of streams to be configured
     * @param kernel             Greengrass kernel
     * @param executorService    Executor Service
     * @param mqttClientKeyStore KeyStore for MQTT Client
     */
    @Inject
    public SMBridge(Topics topics, TopicMapping topicMapping, StreamDefinition streamDefinition, Kernel kernel,
                    MQTTClientKeyStore mqttClientKeyStore, ExecutorService executorService) {
        this(topics, topicMapping, streamDefinition, new MessageBridge(topicMapping), kernel,
                mqttClientKeyStore, executorService);
    }

    protected SMBridge(Topics topics, TopicMapping topicMapping, StreamDefinition streamDefinition,
                       MessageBridge messageBridge, Kernel kernel, MQTTClientKeyStore mqttClientKeyStore,
                       ExecutorService executorService) {
        super(topics);
        this.topicMapping = topicMapping;
        this.streamDefinition = streamDefinition;
        this.kernel = kernel;
        this.mqttClientKeyStore = mqttClientKeyStore;
        this.messageBridge = messageBridge;
        this.executorService = executorService;
    }

    @Override
    public void install() {
        mappingConfigTopics =
                this.config.lookupTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, MQTT_STREAM_MAPPING);

        streamsConfigTopics =
                this.config.lookupTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, STREAM_DEFINITION);

        mappingConfigTopics.subscribe((whatHappened, node) -> {
            if (mappingConfigTopics.isEmpty()) {
                logger.debug("Mapping empty");
                topicMapping.updateMapping(Collections.emptyMap());
                return;
            }

            try {
                Map<String, TopicMapping.MappingEntry> mapping = OBJECT_MAPPER
                        .convertValue(mappingConfigTopics.toPOJO(),
                                new TypeReference<Map<String, TopicMapping.MappingEntry>>() {
                                });
                logger.atInfo().kv("Mapping", mapping).log("Updating Mapping");
                topicMapping.updateMapping(mapping);
            } catch (IllegalArgumentException e) {
                logger.atError("Invalid topic mapping").kv("TopicMapping", mappingConfigTopics.toString()).log();
                // Currently, Nucleus spills all exceptions in std err which junit consider failures
                serviceErrored(String.format("Invalid topic mapping. %s", e.getMessage()));
            }
        });

        streamsConfigTopics.subscribe((whatHappened, node) -> {
            if (streamsConfigTopics.isEmpty()) {
                logger.debug("Stream definition config empty");
                streamDefinition.updateDefinition(Collections.emptyMap());
                return;
            }

            try {
                Map<String, MessageStreamDefinition> mapping = OBJECT_MAPPER
                        .convertValue(streamsConfigTopics.toPOJO(),
                                new TypeReference<Map<String, MessageStreamDefinition>>() {
                                });
                logger.atInfo().kv("Streams", mapping).log("Updating stream definitions");
                streamDefinition.updateDefinition(mapping);
            } catch (IllegalArgumentException e) {
                logger.atError("Invalid stream definitions").kv("Streams", streamsConfigTopics.toString()).log();
                // Currently, Nucleus spills all exceptions in std err which junit consider failures
                serviceErrored(String.format("Invalid stream definitions. %s", e.getMessage()));
            }
        });
    }

    @Override
    public void startup() {
        try {
            mqttClientKeyStore.init();
        } catch (CsrProcessingException | KeyStoreException | CsrGeneratingException e) {
            serviceErrored(e);
            return;
        }

        try {
            kernel.locate(ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME).getConfig()
                    .lookup(RUNTIME_STORE_NAMESPACE_TOPIC, ClientDevicesAuthService.CERTIFICATES_KEY,
                            ClientDevicesAuthService.AUTHORITIES_TOPIC).subscribe((why, newv) -> {
                        try {
                            List<String> caPemList = (List<String>) newv.toPOJO();
                            if (Utils.isEmpty(caPemList)) {
                                logger.debug("CA list null or empty");
                                return;
                            }
                            mqttClientKeyStore.updateCA(caPemList);
                        } catch (IOException | CertificateException | KeyStoreException e) {
                            logger.atError("Invalid CA list").kv("CAList", Coerce.toString(newv)).log();
                            serviceErrored(String.format("Invalid CA list. %s", e.getMessage()));
                        }
                    });
        } catch (ServiceLoadException e) {
            logger.atError().cause(e).log("Unable to locate {} service while subscribing to CA certificates",
                    ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME);
            serviceErrored(e);
            return;
        }

        try {
            if (mqttClient == null) {
                mqttClient = new MQTTClient(this.config, mqttClientKeyStore, this.executorService);
            }
            mqttClient.start();
            messageBridge.addOrReplaceMqttClient(mqttClient);
        } catch (MQTTClientException e) {
            serviceErrored(e);
            return;
        }
        AtomicInteger port = new AtomicInteger(8088);
        try {
            kernel.locate("aws.greengrass.StreamManager").getConfig()
                    .lookup(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, "port").subscribe((why, newv) -> {
                        String topic = (String) newv.toPOJO();
                        port.set(Integer.parseInt(topic));
                    });
        } catch (ServiceLoadException e) {
            logger.atError().cause(e).log("Unable to locate {} service while subscribing to custom SM port",
                    "aws.greengrass.StreamManager");
        }

        try {
            smClient = new SMClient(this.config, port.intValue(), streamDefinition);
            smClient.start();
            messageBridge.addOrReplaceSMClient(smClient);
        } catch (SMClientException e) {
            serviceErrored(e);
            return;
        }

        reportState(State.RUNNING);
    }

    @Override
    public void shutdown() {
        if (mqttClient != null) {
            mqttClient.stop();
        }
    }
}
