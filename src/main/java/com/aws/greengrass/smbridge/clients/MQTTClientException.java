/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.smbridge.clients;

/**
 * Exception thrown by the MQTT Client.
 */
public class MQTTClientException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    MQTTClientException(String msg, Throwable cause) {
        super(msg, cause);
    }

    MQTTClientException(String msg) {
        super(msg);
    }
}
