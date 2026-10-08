package com.powerbind.backend.unit;

import com.powerbind.backend.config.MqttClientFactoryConfig;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Unit Test (mqtt client factory credentials)")
class MqttClientFactoryConfigTest {

    private MqttClientFactoryConfig configWith(String username, String password) {
        MqttClientFactoryConfig config = new MqttClientFactoryConfig();
        ReflectionTestUtils.setField(config, "brokerUrl", "tcp://localhost:1883");
        ReflectionTestUtils.setField(config, "username", username);
        ReflectionTestUtils.setField(config, "password", password);
        return config;
    }

    private MqttConnectOptions optionsOf(MqttClientFactoryConfig config) {
        DefaultMqttPahoClientFactory factory =
                (DefaultMqttPahoClientFactory) config.mqttClientFactory();
        return factory.getConnectionOptions();
    }

    @Test
    @DisplayName("credentials from mqtt.username/mqtt.password are applied to the connect options")
    void appliesCredentialsWhenConfigured() {
        MqttConnectOptions options = optionsOf(configWith("powerbind-backend", "s3cret-pass"));

        assertEquals("powerbind-backend", options.getUserName());
        assertArrayEquals("s3cret-pass".toCharArray(), options.getPassword());
    }

    @Test
    @DisplayName("blank username keeps the broker connection anonymous (local dev)")
    void staysAnonymousWhenUsernameBlank() {
        MqttConnectOptions options = optionsOf(configWith("", ""));

        assertNull(options.getUserName());
        assertNull(options.getPassword());
    }
}
