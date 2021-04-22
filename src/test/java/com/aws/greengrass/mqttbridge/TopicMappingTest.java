/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttbridge;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class TopicMappingTest {

    @Test
    void GIVEN_mapping_as_json_string_WHEN_updateMapping_THEN_mapping_updated_successfully() throws Exception {
        TopicMapping mapping = new TopicMapping();
        CountDownLatch updateLatch = new CountDownLatch(1);
        mapping.listenToUpdates(updateLatch::countDown);
        mapping.updateMapping("[\n"
                + "  {\"sourceTopic\": \"mqtt/topic\", \"sourceTopicType\": \"LocalMqtt\", \"destTopic\": \"/test/cloud/topic\", \"destTopicType\": \"IotCore\"},\n"
                + "  {\"sourceTopic\": \"mqtt/topic2\", \"sourceTopicType\": \"LocalMqtt\", \"destTopic\": \"/test/pubsub/topic\", \"destTopicType\": \"Pubsub\"},\n"
                + "  {\"sourceTopic\": \"mqtt/topic3\", \"sourceTopicType\": \"LocalMqtt\", \"destTopic\": \"/test/cloud/topic2\", \"destTopicType\": \"IotCore\"}\n"
                + "]");

        Assertions.assertTrue(updateLatch.await(100, TimeUnit.MILLISECONDS));

        List<TopicMapping.MappingEntry> expectedMapping = new ArrayList<>();
        expectedMapping.add(new TopicMapping.MappingEntry("mqtt/topic", TopicMapping.TopicType.LocalMqtt,
                "/test/cloud" + "/topic", TopicMapping.TopicType.IotCore));
        expectedMapping.add(new TopicMapping.MappingEntry("mqtt/topic2", TopicMapping.TopicType.LocalMqtt,
                "/test/pubsub/topic", TopicMapping.TopicType.Pubsub));
        expectedMapping.add(new TopicMapping.MappingEntry("mqtt/topic3", TopicMapping.TopicType.LocalMqtt,
                "/test/cloud/topic2", TopicMapping.TopicType.IotCore));

        assertArrayEquals(expectedMapping.toArray(), mapping.getMapping().toArray());
    }

    @Test
    void GIVEN_invalid_mapping_as_json_string_WHEN_updateMapping_THEN_mapping_not_updated() throws Exception {
        TopicMapping mapping = new TopicMapping();
        CountDownLatch updateLatch = new CountDownLatch(1);
        mapping.listenToUpdates(updateLatch::countDown);

        assertThat(mapping.getMapping().size(), is(equalTo(0)));
        // Updating with invalid mapping (Providing type as Pubsub-Invalid)
        Assertions.assertThrows(IOException.class, () -> mapping.updateMapping("[\n"
                + "  {\"sourceTopic\": \"mqtt/topic\", \"sourceTopicType\": \"LocalMqtt\", \"destTopic\": \"/test/cloud/topic\", \"destTopicType\": \"IotCore\"},\n"
                + "  {\"sourceTopic\": \"mqtt/topic2\", \"sourceTopicType\": \"LocalMqtt\", \"destTopic\": "
                + "\"/test/pubsub/topic\", \"destTopicType\": \"Pubsub-Invalid\"},\n"
                + "  {\"sourceTopic\": \"mqtt/topic3\", \"sourceTopicType\": \"LocalMqtt\", \"destTopic\": \"/test/cloud/topic2\", \"destTopicType\": \"IotCore\"}\n"
                + "]"));

        Assertions.assertFalse(updateLatch.await(100, TimeUnit.MILLISECONDS));

        assertThat(mapping.getMapping().size(), is(equalTo(0)));
    }

    @Test
    void GIVEN_null_mapping_as_json_string_WHEN_updateMapping_THEN_NPE_thrown() throws Exception {
        TopicMapping mapping = new TopicMapping();
        assertThat(mapping.getMapping().size(), is(equalTo(0)));
        Assertions.assertThrows(NullPointerException.class, () -> mapping.updateMapping(null));
        assertThat(mapping.getMapping().size(), is(equalTo(0)));
    }
}
