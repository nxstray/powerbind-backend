package com.powerbind.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.powerbind.backend.data.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;

// Proxies the admin Log page straight to Loki's query_range API, so the
// frontend never needs to know Loki exists (its URL never reaches the
// browser) and stays behind the same JWT + hasRole('ADMIN') gate as
// everything else — instead of a separate, unauthenticated Grafana tab.
@Slf4j
@RestController
@RequestMapping("/api/admin/logs")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin — Logs", description = "Merged Backend/Frontend/IoT log stream, proxied from Loki")
public class AdminLogController {

    private final WebClient webClient;

    public AdminLogController(@Value("${loki.url:http://localhost:3100}") String lokiUrl) {
        this.webClient = WebClient.builder().baseUrl(lokiUrl).build();
    }

    @GetMapping
    @Operation(summary = "Query merged logs — optionally filtered by source, level, text, and time range")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> query(
            @RequestParam(defaultValue = "ALL") String source,   // ALL | BACKEND | FRONTEND | IOT
            @RequestParam(required = false) String level,        // ERROR | WARN | INFO | DEBUG
            @RequestParam(required = false) String search,       // free-text line filter
            @RequestParam(defaultValue = "1h") String since,     // Loki duration, e.g. 15m / 1h / 6h / 24h
            @RequestParam(defaultValue = "300") int limit) {

        String logQl = buildLogQl(source, level, search);

        JsonNode body = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/loki/api/v1/query_range")
                        // logQl contains literal { } (the LogQL stream selector) — passed
                        // as a URI variable ({query} + build(logQl)) so it is encoded as a
                        // VALUE. A raw .queryParam("query", logQl) makes Spring parse the
                        // LogQL braces as URI-template placeholders and throw
                        // IllegalArgumentException, which surfaced as this endpoint's 400.
                        .queryParam("query", "{query}")
                        .queryParam("since", since)
                        .queryParam("limit", limit)
                        .queryParam("direction", "backward")
                        .build(logQl))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorResume(e -> {
                    log.error("[AdminLogs] Loki query failed: {}", e.getMessage());
                    return Mono.empty();
                })
                .block();

        return ResponseEntity.ok(ApiResponse.ok(toFlatEntries(body)));
    }

    // Level is an actual Loki label (see logback-spring.xml), but source isn't —
    // backend/frontend/IoT all share one stream, told apart only by the
    // "[FRONTEND]"/"[IOT-<room>]" prefix each service already logs with. So
    // level becomes a label match and source becomes a line filter.
    private String buildLogQl(String source, String level, String search) {
        StringBuilder selector = new StringBuilder("{app=\"powerbind-backend\"");
        if (level != null && !level.isBlank() && !"ALL".equalsIgnoreCase(level)) {
            selector.append(",level=\"").append(level.toUpperCase()).append("\"");
        }
        selector.append("}");

        switch (source == null ? "ALL" : source.toUpperCase()) {
            case "FRONTEND" -> selector.append(" |= \"[FRONTEND]\"");
            case "IOT" -> selector.append(" |= \"[IOT-\"");
            case "BACKEND" -> selector.append(" !~ \"\\\\[FRONTEND\\\\]|\\\\[IOT-\"");
            default -> { /* ALL — no extra filter */ }
        }

        if (search != null && !search.isBlank()) {
            selector.append(" |= \"").append(search.replace("\"", "")).append("\"");
        }

        return selector.toString();
    }

    private List<Map<String, Object>> toFlatEntries(JsonNode body) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (body == null) return entries;

        JsonNode streams = body.path("data").path("result");
        for (JsonNode stream : streams) {
            String levelLabel = stream.path("stream").path("level").asText("INFO");
            for (JsonNode value : stream.path("values")) {
                long tsNanos = value.get(0).asLong();
                String line = value.get(1).asText();

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("timestampMs", tsNanos / 1_000_000);
                entry.put("level", levelLabel);
                entry.put("source", detectSource(line));
                entry.put("message", line);
                entries.add(entry);
            }
        }

        entries.sort(Comparator.comparingLong(e -> (long) e.get("timestampMs")));
        return entries;
    }

    private String detectSource(String line) {
        if (line.contains("[FRONTEND]")) return "FRONTEND";
        if (line.contains("[IOT-")) return "IOT";
        return "BACKEND";
    }
}
