package com.powerbind.backend.data.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

// Pushed over WebSocket (/topic/anomaly) the moment a room transitions into a
// waste state — relay ON while presence is EMPTY. Built from real Room state,
// not from any AI-generated text.
@Getter
@Builder
public class AnomalyEventResponse {
    private String roomId;
    private String roomName;
    private String message;
    private LocalDateTime detectedAt;
}