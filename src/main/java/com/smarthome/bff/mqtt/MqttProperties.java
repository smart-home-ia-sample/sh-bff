package com.smarthome.bff.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bff.mqtt")
public record MqttProperties(
        boolean enabled,
        String host,
        int port,
        String username,
        String password,
        long commandTimeoutMs
) {
}
