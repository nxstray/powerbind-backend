package com.powerbind.backend.config;

import com.influxdb.client.InfluxDBClient;
import com.powerbind.backend.service.InfluxDBService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

// Replaces real MQTT and InfluxDB beans with mocks during tests
// Prevents connection errors when running tests without real infrastructure
@TestConfiguration
@Profile("test")
public class TestConfig {

    // Mock InfluxDB client — no real connection needed during tests
    @Bean
    @Primary
    public InfluxDBClient influxDBClient() {
        return Mockito.mock(InfluxDBClient.class);
    }

    // Mock InfluxDB service — returns safe default values
    @Bean
    @Primary
    public InfluxDBService influxDBService(InfluxDBClient influxDBClient) {
        return Mockito.mock(InfluxDBService.class);
    }
}