package com.powerbind.backend.data.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class AgentRequest {

    @Getter
    @Setter
    public static class Chat {
        @NotBlank(message = "Message is required")
        private String message;

        // Optional conversation history for multi-turn context
        private List<Turn> history;

        // Existing conversation to continue — null/blank starts a new one
        private String conversationId;
    }

    @Getter
    @Setter
    public static class Turn {
        private String role;    // "user" or "assistant"
        private String content;
    }
}