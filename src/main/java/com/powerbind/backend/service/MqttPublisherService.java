package com.powerbind.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

// Publishes outbound MQTT commands to the ESP32 units — currently only used for
// manual relay on/off from the dashboard. Inbound presence/power data still flows
// through MqttConfig's inbound adapter; this is the outbound counterpart.
@Slf4j
@Service
@RequiredArgsConstructor
public class MqttPublisherService {

    private final MessageChannel mqttOutboundChannel;

    public void publishRelayCommand(String mqttTopic, boolean relayOn) {
        // use a dedicated "relay" namespace so this outbound command topic never
    // falls under mqtt.topic.power's wildcard subscription and gets echoed
    // back into handlePower() as telemetry
    String roomSlug = mqttTopic.substring(mqttTopic.lastIndexOf('/') + 1);
        String commandTopic = "smart-home/power/" + mqttTopic + "/set";
        String payload = relayOn ? "ON" : "OFF";

        try {
            Message<String> message = MessageBuilder
                    .withPayload(payload)
                    .setHeader(MqttHeaders.TOPIC, commandTopic)
                    .build();
            mqttOutboundChannel.send(message);
        } catch (MessagingException e) {
            // Don't fail the request just because the broker/device is unreachable right
            // now — the DB state (source of truth for the dashboard) is already saved.
            log.error("[MQTT] Failed to publish relay command to {}: {}", commandTopic, e.getMessage());
        }
    }
}