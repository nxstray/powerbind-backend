package com.powerbind.backend.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;

// standalone home for the shared paho client factory bean. it used to live inside
// MqttConfig, but that made MqttOutboundConfig (which needs this bean) indirectly
// depend on MqttConfig getting fully constructed — and MqttConfig depends on
// MqttMessageHandler -> RoomService -> MqttPublisherService -> MqttOutboundConfig,
// closing the loop. this class has zero dependencies on that chain, so both
// MqttConfig and MqttOutboundConfig can depend on it without creating a cycle.
@Configuration
public class MqttClientFactoryConfig {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MqttClientFactoryConfig.class);

    @Value("${mqtt.broker-url}")
    private String brokerUrl;

    // empty = anonymous connection (local dev against the native broker);
    // docker-compose sets both because the containerized Mosquitto runs
    // with allow_anonymous=false
    @Value("${mqtt.username:}")
    private String username;

    @Value("${mqtt.password:}")
    private String password;

    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{brokerUrl});
        options.setCleanSession(true);
        // automatically reconnect if broker restarts — no manual intervention needed
        options.setAutomaticReconnect(true);
        // keep trying to reconnect every 10 seconds if broker is unavailable at startup
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        if (username != null && !username.isBlank()) {
            options.setUserName(username);
            if (password != null && !password.isBlank()) {
                options.setPassword(password.toCharArray());
            }
            log.info("[MQTT] Authenticating to broker as user '{}'", username);
        }
        factory.setConnectionOptions(options);
        return factory;
    }
}