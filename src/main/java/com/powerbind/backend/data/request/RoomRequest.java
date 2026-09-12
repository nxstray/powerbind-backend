package com.powerbind.backend.data.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

public class RoomRequest {

    @Getter
    @Setter
    public static class Create {
        @NotBlank(message = "Room name is required")
        private String name;

        @NotBlank(message = "MQTT topic is required")
        private String mqttTopic;
    }

    @Getter
    @Setter
    public static class Update {
        @NotBlank(message = "Room name is required")
        private String name;
    }

    // Manual relay control from the dashboard — turns a room's device on/off
    @Getter
    @Setter
    public static class RelayControl {
        @NotNull(message = "relayOn is required")
        private Boolean relayOn;
    }
}