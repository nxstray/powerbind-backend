package com.powerbind.backend.unit;

import com.powerbind.backend.data.response.RoomResponse;
import com.powerbind.backend.model.Room;
import com.powerbind.backend.service.InfluxDBService;
import com.powerbind.backend.service.MqttMessageHandler;
import com.powerbind.backend.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (mqtt message handler)")
@ExtendWith(MockitoExtension.class)
class MqttMessageHandlerTest {

    @Mock private RoomService roomService;
    @Mock private InfluxDBService influxDBService;
    @Mock private SimpMessagingTemplate websocket;

    @InjectMocks private MqttMessageHandler handler;

    private Room room;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(handler, "presenceTopic", "smart-home/presence/#");
        ReflectionTestUtils.setField(handler, "powerTopic", "smart-home/power/#");

        room = Room.builder()
                .id(UUID.randomUUID())
                .name("Kamar 1")
                .mqttTopic("smart-home/presence/room1")
                .presenceDetected(true)
                .relayOn(true)
                .build();
    }

    private Message<String> message(String topic, String payload) {
        return new GenericMessage<>(payload, Map.of("mqtt_receivedTopic", topic));
    }

    @Test
    @DisplayName("TC-UNIT-MQTT-01 message without a topic header is ignored")
    void handle_shouldIgnore_whenTopicMissing() {
        handler.handle(new GenericMessage<>("payload"));

        verifyNoInteractions(roomService, influxDBService, websocket);
    }

    @Test
    @DisplayName("TC-UNIT-MQTT-02 device log messages are only logged, not processed")
    void handle_shouldOnlyLog_deviceLogTopic() {
        handler.handle(message("smart-home/logs/esp32/room1", "BOOT complete"));

        verifyNoInteractions(roomService, influxDBService, websocket);
    }

    @Test
    @DisplayName("TC-UNIT-MQTT-03 presence '1' updates the room, writes Influx, and pushes WebSocket")
    void handlePresence_shouldProcessPresenceDetected() {
        when(roomService.updatePresence("smart-home/presence/room1", true)).thenReturn(room);

        handler.handle(message("smart-home/presence/room1", "1"));

        verify(influxDBService).writePresence("Kamar 1", true);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(websocket).convertAndSend(eq("/topic/presence"), payloadCaptor.capture());
        RoomResponse.Status status = (RoomResponse.Status) payloadCaptor.getValue();
        assertEquals(room.getId().toString(), status.getId());
        assertEquals("Kamar 1", status.getName());
        assertTrue(status.isPresenceDetected());
        assertTrue(status.isRelayOn());
    }

    @Test
    @DisplayName("TC-UNIT-MQTT-04 presence '0' is forwarded as not-detected")
    void handlePresence_shouldForwardNotDetected() {
        room.setPresenceDetected(false);
        room.setRelayOn(false);
        when(roomService.updatePresence("smart-home/presence/room1", false)).thenReturn(room);

        handler.handle(message("smart-home/presence/room1", "0"));

        verify(influxDBService).writePresence("Kamar 1", false);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(websocket).convertAndSend(eq("/topic/presence"), payloadCaptor.capture());
        RoomResponse.Status status = (RoomResponse.Status) payloadCaptor.getValue();
        assertFalse(status.isPresenceDetected());
    }

    @Test
    @DisplayName("TC-UNIT-MQTT-05 a failing presence update is swallowed (device stream keeps running)")
    void handlePresence_shouldSwallowErrors() {
        when(roomService.updatePresence(anyString(), anyBoolean())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> handler.handle(message("smart-home/presence/room1", "1")));

        verify(websocket, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("TC-UNIT-MQTT-06 valid power telemetry is written to Influx and pushed to WebSocket")
    void handlePower_shouldProcessTelemetry() {
        handler.handle(message("smart-home/power/room1", "120.5,220.1,0.55,1.25"));

        verify(influxDBService).writePower(120.5, 220.1, 0.55, 1.25);
        verify(websocket).convertAndSend("/topic/power", "120.5,220.1,0.55,1.25");
    }

    @Test
    @DisplayName("TC-UNIT-MQTT-07 non-telemetry power payloads (< 4 fields) are ignored")
    void handlePower_shouldIgnoreShortPayloads() {
        handler.handle(message("smart-home/power/room1", "120.5,220.1"));

        verifyNoInteractions(influxDBService, websocket);
    }

    @Test
    @DisplayName("TC-UNIT-MQTT-08 malformed power values do not crash the handler")
    void handlePower_shouldSwallowParseErrors() {
        assertDoesNotThrow(() ->
                handler.handle(message("smart-home/power/room1", "not,numeric,values,here")));

        verifyNoInteractions(influxDBService, websocket);
    }

    @Test
    @DisplayName("TC-UNIT-MQTT-09 topics outside presence/power/logs are ignored")
    void handle_shouldIgnore_unknownTopicPrefix() {
        handler.handle(message("smart-home/other/room1", "whatever"));

        verifyNoInteractions(roomService, influxDBService, websocket);
    }
}
