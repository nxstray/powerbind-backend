package com.powerbind.backend.data.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class ErdExplainRequest {

    @Getter
    @Setter
    public static class Column {
        @NotBlank(message = "Table name is required")
        private String table;

        @NotBlank(message = "Column name is required")
        private String column;

        // Optional SQL type (uuid, varchar, timestamp, ...) for extra context
        private String type;

        private boolean primaryKey;

        private boolean foreignKey;

        // Relations already matched by the frontend from the ERD schema
        // (e.g. "chat_messages.user_id -> users.id") — the AI explains these
        // known facts instead of guessing
        private List<String> relations;
    }
}
