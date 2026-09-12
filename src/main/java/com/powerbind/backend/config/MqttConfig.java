package com.powerbind.backend.config;

import com.powerbind.backend.service.MqttMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

// MQTT inbound channel adapter — subscribes to ESP32 topics via Mosquitto broker.
// Outbound publish beans live in MqttOutboundConfig (separate class) on purpose:
// this class depends on MqttMessageHandler, which (via RoomService -> MqttPublisherService)
// depends back on the outbound channel bean — keeping them together creates a
// circular bean dependency at startup. The shared client factory bean lives in
// MqttClientFactoryConfig for the same reason (see comment there).
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MqttConfig {

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.topic.presence}")
    private String presenceTopic;

    @Value("${mqtt.topic.power}")
    private String powerTopic;

    @Value("${mqtt.topic.logs:smart-home/logs/#}")
    private String logsTopic;

    private final MqttMessageHandler mqttMessageHandler;
    private final MqttPahoClientFactory mqttClientFactory;

    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter mqttInbound() {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                clientId + "-inbound",
                mqttClientFactory,
                presenceTopic,
                powerTopic,
                logsTopic
        );
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInputChannel());
        return adapter;
    }

    // route incoming MQTT messages to the handler service
    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler mqttMessageHandlerBean() {
        return (Message<?> message) -> {
            try {
                mqttMessageHandler.handle(message);
            } catch (Exception e) {
                // log and swallow — prevents one bad message from killing the listener
                log.error("[MQTT] Error processing message: {}", e.getMessage(), e);
            }
        };
    }
}