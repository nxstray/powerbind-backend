package com.powerbind.backend.data.request;

import jakarta.validation.constraints.NotBlank;
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
}
