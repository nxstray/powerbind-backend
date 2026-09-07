package com.powerbind.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

// Handles all Groq API calls — chat streaming, vision, Whisper transcription, and
// one-shot JSON completions (used for background memory extraction)
@Slf4j
@Service
public class GroqService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${groq.model}")
    private String model;

    @Value("${groq.vision.model}")
    private String visionModel;

    @Value("${groq.whisper.model}")
    private String whisperModel;

    @Value("${groq.max-tokens}")
    private int maxTokens;

    public GroqService(@Value("${groq.api.url}") String baseUrl,
                       @Value("${groq.api.key}") String apiKey) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    // stream chat completion — text only
    public Flux<String> streamChat(List<Map<String, Object>> messages) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "max_tokens", maxTokens,
                "stream", true,
                "temperature", 0.7
        );

        // WebClient's ServerSentEventHttpMessageReader already splits the SSE stream
        // into complete "data:" payloads and strips the prefix for us — each element
        // here is already one full JSON chunk, no manual line-buffering needed
        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(payload -> !payload.isBlank() && !"[DONE]".equals(payload.trim()))
                .mapNotNull(this::extractDeltaContent)
                .doOnError(e -> log.error("[Groq] Stream error: {}", e.getMessage()))
                .onErrorResume(e -> Flux.just("Maaf, AI Agent sedang mengalami kendala (Koneksi ke server Groq gagal)."));
    }


    // Stream chat with vision — accepts image URL or base64
    public Flux<String> streamVisionChat(String textPrompt, String imageBase64) {
        List<Map<String, Object>> content = List.of(
                Map.of("type", "text", "text", textPrompt),
                Map.of("type", "image_url", "image_url",
                        Map.of("url", "data:image/jpeg;base64," + imageBase64))
        );

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", content)
        );

        Map<String, Object> body = Map.of(
                "model", visionModel,
                "messages", messages,
                "max_tokens", maxTokens,
                "stream", true
        );

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(chunk -> !chunk.isBlank())
                .mapNotNull(this::extractDeltaContent)
                .doOnError(e -> log.error("[Groq Vision] Stream error: {}", e.getMessage()))
                // Graceful fallback to prevent throwing exception to Spring MVC which causes the 401 redirect
                .onErrorResume(e -> Flux.just("Maaf, AI Agent sedang mengalami kendala (Koneksi ke server Groq gagal)."));
    }

    // Transcribe audio via Whisper — returns transcribed text
    public String transcribe(MultipartFile audioFile) {
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", audioFile.getResource());
            builder.part("model", whisperModel);
            builder.part("response_format", "json");
            builder.part("language", "id"); // Indonesian default, auto-detect if mixed

            String response = webClient.post()
                    .uri("/audio/transcriptions")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // Extract text from JSON response: {"text": "..."}
            if (response != null && response.contains("\"text\"")) {
                int start = response.indexOf("\"text\":\"") + 8;
                int end = response.lastIndexOf("\"");
                return response.substring(start, end);
            }
            return "";
        } catch (Exception e) {
            log.error("[Groq Whisper] Transcription error: {}", e.getMessage());
            return "";
        }
    }

    // One-shot (non-streaming) completion forced into JSON output — used for background
    // tasks like memory extraction where we need a structured, parseable result rather
    // than a token stream. Returns the raw JSON string from the assistant, or null on failure.
    public String completeJson(String systemPrompt, String userContent) {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        );

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "max_tokens", 400,
                "temperature", 0.2,
                "stream", false,
                "response_format", Map.of("type", "json_object")
        );

        try {
            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null) return null;
            JsonNode content = objectMapper.readTree(response)
                    .path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : null;
        } catch (Exception e) {
            log.error("[Groq] JSON completion error: {}", e.getMessage());
            return null;
        }
    }

    // extract content delta from one complete SSE line, using proper JSON parsing
    // so escaped quotes/unicode inside the content field don't break extraction
    private String extractDeltaContent(String json) {
        try {
            JsonNode delta = objectMapper.readTree(json)
                    .path("choices").path(0).path("delta");
            JsonNode content = delta.path("content");

            return content.isTextual() ? content.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}