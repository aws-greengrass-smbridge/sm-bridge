/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.smbridge;

import com.aws.greengrass.certificatemanager.CertificateManager;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import com.aws.greengrass.util.Utils;
import io.moquette.BrokerConstants;
import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class SMBridgeTest extends GGServiceTestUtil {
    private static final long TEST_TIME_OUT_SEC = 10L;

    private Kernel kernel;
    private Server broker;

    @TempDir
    Path rootDir;

    @Mock
    CertificateManager mockCertificateManager;

    @BeforeEach
    void setup() throws IOException {
        kernel = new Kernel();
        kernel.getContext().put(CertificateManager.class, mockCertificateManager);
        IConfig defaultConfig = new MemoryConfig(new Properties());
        defaultConfig.setProperty(BrokerConstants.PORT_PROPERTY_NAME, "8883");
        broker = new Server();
        broker.startServer(defaultConfig);
        // ses = new ScheduledThreadPoolExecutor(1);
    }

    @AfterEach
    void cleanup() {
        kernel.shutdown();
        broker.stopServer();
    }

    private void startKernelWithConfig(String configFileName) throws InterruptedException {
        CountDownLatch bridgeRunning = new CountDownLatch(1);
        kernel.parseArgs("-r", rootDir.toAbsolutePath().toString(), "-i",
                getClass().getResource(configFileName).toString());
        GlobalStateChangeListener listener = (GreengrassService service, State was, State newState) -> {
            if (service.getName().equals(SMBridge.SERVICE_NAME) && service.getState().equals(State.RUNNING)) {
                bridgeRunning.countDown();
            }
        };
        kernel.getContext().addGlobalStateChangeListener(listener);
        kernel.launch();

        Assertions.assertTrue(bridgeRunning.await(TEST_TIME_OUT_SEC, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_Greengrass_with_sm_bridge_WHEN_start_kernel_THEN_bridge_starts_successfully() throws Exception {
        startKernelWithConfig("config.yaml");
    }

    @Test
    void GIVEN_Greengrass_with_sm_bridge_WHEN_valid_mqttStreamMapping_updated_THEN_mapping_updated() throws Exception {
        startKernelWithConfig("config.yaml");
        TopicMapping topicMapping = ((SMBridge) kernel.locate(SMBridge.SERVICE_NAME)).getTopicMapping();
        assertThat(topicMapping.getMapping().size(), is(equalTo(0)));

        Topics mappingConfigTopics = kernel.locate(SMBridge.SERVICE_NAME).getConfig()
                .lookupTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, SMBridge.MQTT_STREAM_MAPPING);

        mappingConfigTopics.replaceAndWait(Utils.immutableMap("m1",
                Utils.immutableMap("topic", "mqtt/topic", "stream", "randomStream",
                        "appendTime", false, "appendTopic", false), "m2",
                Utils.immutableMap("topic", "mqtt/topi2", "stream", "randomStream2",
                        "appendTime", true, "appendTopic", false), "m3",
                Utils.immutableMap("topic", "mqtt/topic3", "stream", "randomStream",
                        "appendTime", false, "appendTopic", true)));

        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });
        assertThat(topicMapping.getMapping().size(), is(equalTo(3)));
    }
/*
    @Test
    void GIVEN_Greengrass_with_mqtt_bridge_WHEN_valid_mapping_provided_in_config_THEN_mapping_populated()
            throws Exception {
        startKernelWithConfig("config_with_mapping.yaml");
        TopicMapping topicMapping = ((MQTTBridge) kernel.locate(MQTTBridge.SERVICE_NAME)).getTopicMapping();

        assertThat(() -> topicMapping.getMapping().size(), EventuallyLambdaMatcher.eventuallyEval(is(3)));
        Map<String, TopicMapping.MappingEntry> expectedMapping = new HashMap<>();
        expectedMapping.put("mapping1",
                new TopicMapping.MappingEntry("topic/to/map/from/local/to/cloud", TopicMapping.TopicType.LocalMqtt,
                        TopicMapping.TopicType.IotCore));
        expectedMapping.put("mapping2",
                new TopicMapping.MappingEntry("topic/to/map/from/local/to/pubsub", TopicMapping.TopicType.LocalMqtt,
                        TopicMapping.TopicType.Pubsub));
        expectedMapping.put("mapping3",
                new TopicMapping.MappingEntry("topic/to/map/from/local/to/cloud/2", TopicMapping.TopicType.LocalMqtt,
                        TopicMapping.TopicType.IotCore));

        assertEquals(expectedMapping, topicMapping.getMapping());
    }

    @Test
    void GIVEN_Greengrass_with_mqtt_bridge_WHEN_empty_mqttTopicMapping_updated_THEN_mapping_not_updated()
            throws Exception {
        startKernelWithConfig("config.yaml");
        TopicMapping topicMapping = ((MQTTBridge) kernel.locate(MQTTBridge.SERVICE_NAME)).getTopicMapping();
        assertThat(topicMapping.getMapping().size(), is(equalTo(0)));

        kernel.locate(MQTTBridge.SERVICE_NAME).getConfig()
                .lookupTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, MQTTBridge.MQTT_TOPIC_MAPPING)
                .replaceAndWait(Collections.EMPTY_MAP);
        // Block until subscriber has finished updating
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });
        assertThat(topicMapping.getMapping().size(), is(equalTo(0)));
    }

    @Test
    void GIVEN_Greengrass_with_mqtt_bridge_WHEN_mapping_updated_with_empty_THEN_mapping_removed()
            throws Exception {
        startKernelWithConfig("config_with_mapping.yaml");
        TopicMapping topicMapping = ((MQTTBridge) kernel.locate(MQTTBridge.SERVICE_NAME)).getTopicMapping();

        assertThat(() -> topicMapping.getMapping().size(), EventuallyLambdaMatcher.eventuallyEval(is(3)));
        kernel.locate(MQTTBridge.SERVICE_NAME).getConfig()
                .lookupTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, MQTTBridge.MQTT_TOPIC_MAPPING)
                .replaceAndWait(Collections.EMPTY_MAP);
        // Block until subscriber has finished updating
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });
        assertThat(topicMapping.getMapping().size(), is(equalTo(0)));
    }

    @Test
    void GIVEN_Greengrass_with_mqtt_bridge_WHEN_invalid_mqttTopicMapping_updated_THEN_mapping_not_updated()
            throws Exception {
        startKernelWithConfig("config.yaml");
        TopicMapping topicMapping = ((MQTTBridge) kernel.locate(MQTTBridge.SERVICE_NAME)).getTopicMapping();
        assertThat(topicMapping.getMapping().size(), is(equalTo(0)));

        kernel.getContext().removeGlobalStateChangeListener(listener);
        CountDownLatch bridgeErrored = new CountDownLatch(1);

        GlobalStateChangeListener listener = (GreengrassService service, State was, State newState) -> {
            if (service.getName().equals(MQTTBridge.SERVICE_NAME) && service.getState().equals(State.ERRORED)) {
                bridgeErrored.countDown();
            }
        };

        kernel.getContext().addGlobalStateChangeListener(listener);

        // Updating with invalid mapping (Providing type as Pubsub-Invalid)
        Topics mappingConfigTopics = kernel.locate(MQTTBridge.SERVICE_NAME).getConfig()
                .lookupTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY, MQTTBridge.MQTT_TOPIC_MAPPING);

        mappingConfigTopics.replaceAndWait(Utils.immutableMap("m1",
                Utils.immutableMap("topic", "mqtt/topic", "source", TopicMapping.TopicType.LocalMqtt.toString(),
                        "target", TopicMapping.TopicType.IotCore.toString()), "m2",
                Utils.immutableMap("topic", "mqtt/topic2", "source", TopicMapping.TopicType.LocalMqtt.toString(),
                        "target", "Pubsub-Invalid"), "m3",
                Utils.immutableMap("topic", "mqtt/topic3", "source", TopicMapping.TopicType.LocalMqtt.toString(),
                        "target", TopicMapping.TopicType.IotCore.toString())));

        // Block until subscriber has finished updating
        kernel.getContext().runOnPublishQueueAndWait(() -> {
        });
        assertThat(topicMapping.getMapping().size(), is(equalTo(0)));

        Assertions.assertTrue(bridgeErrored.await(TEST_TIME_OUT_SEC, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_Greengrass_with_mqtt_bridge_WHEN_CAs_updated_THEN_KeyStore_updated() throws Exception {
        serviceFullName = MQTTBridge.SERVICE_NAME;
        initializeMockedConfig();
        TopicMapping mockTopicMapping = mock(TopicMapping.class);
        MessageBridge mockMessageBridge = mock(MessageBridge.class);
        PubSubIPCEventStreamAgent mockPubSubIPCAgent = mock(PubSubIPCEventStreamAgent.class);
        Kernel mockKernel = mock(Kernel.class);
        MQTTClientKeyStore mockMqttClientKeyStore = mock(MQTTClientKeyStore.class);
        MQTTBridge mqttBridge;
        try (MqttClient mockIotMqttClient = mock(MqttClient.class)) {
            mqttBridge =
                    new MQTTBridge(config, mockTopicMapping, mockMessageBridge, mockPubSubIPCAgent, mockIotMqttClient,
                            mockKernel, mockMqttClientKeyStore, ses);
        }

        when(config
                .findOrDefault(any(), eq(KernelConfigResolver.CONFIGURATION_CONFIG_KEY), eq(MQTTClient.BROKER_URI_KEY)))
                .thenReturn("tcp://localhost:8883");
        when(config
                .findOrDefault(any(), eq(KernelConfigResolver.CONFIGURATION_CONFIG_KEY), eq(MQTTClient.CLIENT_ID_KEY)))
                .thenReturn(MQTTBridge.SERVICE_NAME);

        ClientDevicesAuthService mockClientAuthService = mock(ClientDevicesAuthService.class);
        when(mockKernel.locate(ClientDevicesAuthService.CLIENT_DEVICES_AUTH_SERVICE_NAME))
                .thenReturn(mockClientAuthService);
        Topics mockClientAuthConfig = mock(Topics.class);
        when(mockClientAuthService.getConfig()).thenReturn(mockClientAuthConfig);

        Topic caTopic = Topic.of(context, "authorities", Arrays.asList("CA1", "CA2"));
        when(mockClientAuthConfig
                .lookup(MQTTBridge.RUNTIME_STORE_NAMESPACE_TOPIC, ClientDevicesAuthService.CERTIFICATES_KEY,
                        ClientDevicesAuthService.AUTHORITIES_TOPIC)).thenReturn(caTopic);
        mqttBridge.startup();
        mqttBridge.shutdown();
        ArgumentCaptor<List<String>> caListCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockMqttClientKeyStore).updateCA(caListCaptor.capture());
        assertThat(caListCaptor.getValue(), is(Arrays.asList("CA1", "CA2")));

        caTopic = Topic.of(context, "authorities", Collections.emptyList());
        when(mockClientAuthConfig
                .lookup(MQTTBridge.RUNTIME_STORE_NAMESPACE_TOPIC, ClientDevicesAuthService.CERTIFICATES_KEY,
                        ClientDevicesAuthService.AUTHORITIES_TOPIC)).thenReturn(caTopic);
        reset(mockMqttClientKeyStore);
        mqttBridge.startup();
        mqttBridge.shutdown();
        verify(mockMqttClientKeyStore, never()).updateCA(caListCaptor.capture());
    }
 */
}
