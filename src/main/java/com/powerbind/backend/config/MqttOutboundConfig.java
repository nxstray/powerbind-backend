package com.powerbind.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

// Outbound MQTT publish side — kept in its own @Configuration, separate from
// MqttConfig (inbound). MqttConfig depends on MqttMessageHandler, which (via
// RoomService -> MqttPublisherService) depends back on this outbound channel bean.
// This class only depends on MqttPahoClientFactory, sourced from the standalone
// MqttClientFactoryConfig (not from MqttConfig), so no cycle forms.
@Configuration
@RequiredArgsConstructor
public class MqttOutboundConfig {

    @Value("${mqtt.client-id}")
    private String clientId;

    private final MqttPahoClientFactory mqttClientFactory;

    @Bean
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttOutbound() {
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(clientId + "-outbound", mqttClientFactory);
        handler.setAsync(true);
        handler.setDefaultQos(1);
        return handler;
    }
}