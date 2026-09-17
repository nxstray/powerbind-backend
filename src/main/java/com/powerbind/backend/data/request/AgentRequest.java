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

    @Getter
    @Setter
    public static class Rename {
        @NotBlank(message = "Title is required")
        private String title;
    }

    // Ephemeral one-shot Q&A (Metrics page overlay) — streamed but never persisted.
    // metrics/hours are optional context: when present the backend pulls the ACTUAL
    // Prometheus data for those charts so the AI answers from real values, not guesses.
    @Getter
    @Setter
    public static class QuickAsk {
        @NotBlank(message = "Message is required")
        private String message;

        // Metric names of the charts currently displayed (optional)
        private List<String> metrics;

        // Time range of the charts in hours (optional, clamped server-side)
        private Integer hours;
    }
}