package com.powerbind.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Human-readable room name, e.g. "Kamar 1", "Ruang Tamu"
    @Column(nullable = false)
    private String name;

    // MQTT topic suffix used to identify this room's ESP32
    @Column(name = "mqtt_topic", nullable = false, unique = true)
    private String mqttTopic;

    // Whether presence was last detected in this room
    @Column(name = "presence_detected", nullable = false)
    @Builder.Default
    private boolean presenceDetected = false;

    // Whether the relay/device in this room is currently ON
    @Column(name = "relay_on", nullable = false)
    @Builder.Default
    private boolean relayOn = false;

    // Seconds elapsed since last presence detection (for auto-off logic)
    @Column(name = "no_presence_seconds", nullable = false)
    @Builder.Default
    private int noPresenceSeconds = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
