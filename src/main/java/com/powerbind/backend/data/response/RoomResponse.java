package com.powerbind.backend.data.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

public class RoomResponse {

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Detail {
        private String id;
        private String name;
        private String mqttTopic;
        private boolean presenceDetected;
        private boolean relayOn;
        private int noPresenceSeconds;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Status {
        private String id;
        private String name;
        private boolean presenceDetected;
        private boolean relayOn;
    }
}
