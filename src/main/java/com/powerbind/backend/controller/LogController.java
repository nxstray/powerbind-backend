package com.powerbind.backend.controller;

import com.powerbind.backend.data.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// Receives log entries from the Vue.js frontend and forwards them to Loki via SLF4J
@Slf4j
@RestController
@RequestMapping("/api/logs")
@Tag(name = "Logs", description = "Frontend log ingestion endpoint")
public class LogController {

    @PostMapping
    @Operation(summary = "Receive a log entry from the frontend")
    public ResponseEntity<ApiResponse<Void>> receiveLog(@RequestBody Map<String, String> body) {
        String level = body.getOrDefault("level", "INFO").toUpperCase();
        String message = body.getOrDefault("message", "");

        // Route to the correct log level so Loki labels are accurate
        switch (level) {
            case "ERROR" -> log.error("[FRONTEND] {}", message);
            case "WARN"  -> log.warn("[FRONTEND] {}", message);
            case "DEBUG" -> log.debug("[FRONTEND] {}", message);
            default      -> log.info("[FRONTEND] {}", message);
        }

        return ResponseEntity.ok(ApiResponse.ok("Log received"));
    }
}