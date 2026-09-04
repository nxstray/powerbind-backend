package com.powerbind.backend.service;

import com.powerbind.backend.data.response.RoomResponse;
import com.powerbind.backend.model.Room;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

// Processes all incoming MQTT messages from ESP32 devices
@Slf4j
@Service
@RequiredArgsConstructor
public class MqttMessageHandler {

    private final RoomService roomService;
    private final InfluxDBService influxDBService;
    private final SimpMessagingTemplate websocket;

    @Value("${mqtt.topic.presence}")
    private String presenceTopic;

    @Value("${mqtt.topic.power}")
    private String powerTopic;

    public void handle(Message<?> message) {
        String topic = (String) message.getHeaders().get("mqtt_receivedTopic");
        String payload = message.getPayload().toString().trim();

        if (topic == null) return;

        // Pisahkan log khusus agar tidak tercampur dengan log debug MQTT biasa
        if (topic.startsWith("smart-home/logs")) {
            String roomNode = topic.substring(topic.lastIndexOf("/") + 1);
            log.info("[IOT-{}] {}", roomNode.toUpperCase(), payload);
            return; // Selesai diproses
        }

        log.debug("[MQTT] Received on topic {}: {}", topic, payload);

        if (topic.startsWith(presenceTopic.replace("#", "").replace("+", ""))) {
            handlePresence(topic, payload);
        } else if (topic.startsWith(powerTopic.replace("#", "").replace("+", ""))) {
            handlePower(payload);
        }
    }

    // Handle presence message — payload is "1" (detected) or "0" (not detected)
    private void handlePresence(String topic, String payload) {
        try {
            boolean detected = "1".equals(payload);
            Room updatedRoom = roomService.updatePresence(topic, detected);

            // Write to InfluxDB for historical charts
            influxDBService.writePresence(updatedRoom.getName(), detected);

            // Push real-time update to Vue.js dashboard via WebSocket
            RoomResponse.Status status = RoomResponse.Status.builder()
                    .id(updatedRoom.getId().toString())
                    .name(updatedRoom.getName())
                    .presenceDetected(updatedRoom.isPresenceDetected())
                    .relayOn(updatedRoom.isRelayOn())
                    .build();

            websocket.convertAndSend("/topic/presence", status);

        } catch (Exception e) {
            log.error("[MQTT] Error handling presence message: {}", e.getMessage());
        }
    }

    // Handle power message — payload format: "watts,voltage,current,kwh"
    private void handlePower(String payload) {
        try {
            String[] parts = payload.split(",");
            if (parts.length < 4) {
                log.warn("[MQTT] Invalid power payload: {}", payload);
                return;
            }

            double watts = Double.parseDouble(parts[0]);
            double voltage = Double.parseDouble(parts[1]);
            double current = Double.parseDouble(parts[2]);
            double kwh = Double.parseDouble(parts[3]);

            // Write to InfluxDB
            influxDBService.writePower(watts, voltage, current, kwh);

            // Push real-time power update to dashboard
            websocket.convertAndSend("/topic/power", payload);

        } catch (Exception e) {
            log.error("[MQTT] Error handling power message: {}", e.getMessage());
        }
    }
}
