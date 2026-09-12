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

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import java.time.Duration;
import java.util.ArrayList;
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

    // Applied globally so that any AI page (Gemono chat, vision, ERD explain, or
    // any future feature routed through streamChat/streamVisionChat) can never
    // reply with emoji, regardless of what each page's own system prompt says.
    private static final String NO_EMOJI_INSTRUCTION =
            "Jangan gunakan emoji sama sekali dalam jawabanmu, dalam kondisi apa pun.";

    private List<Map<String, Object>> withNoEmojiInstruction(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>(messages);
        result.add(Map.of("role", "system", "content", NO_EMOJI_INSTRUCTION));
        return result;
    }

    // stream chat completion — text only
    public Flux<String> streamChat(List<Map<String, Object>> messages) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", withNoEmojiInstruction(messages),
                "max_tokens", maxTokens,
                "stream", true,
                "temperature", 0.7
        );

        // WebClient's ServerSentEventHttpMessageReader already splits the SSE stream
        // into complete "data:" payloads and strips the prefix for us — each element
        // here is already one full JSON chunk, no manual line-buffering needed.
        // timeout(): kalau stream menggantung (tidak ada data 60 detik), batalkan
        // dan lempar error — onErrorResume di bawah yang mengubahnya jadi pesan.
        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(60))
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
                "messages", withNoEmojiInstruction(messages),
                "max_tokens", maxTokens,
                "stream", true
        );

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(60))
                .filter(chunk -> !chunk.isBlank())
                .mapNotNull(this::extractDeltaContent)
                .doOnError(e -> log.error("[Groq Vision] Stream error: {}", e.getMessage()))
                // Graceful fallback to prevent throwing exception to Spring MVC which causes the 401 redirect
                .onErrorResume(e -> Flux.just("Maaf, AI Agent sedang mengalami kendala (Koneksi ke server Groq gagal)."));
    }

    // Transcribe audio via Whisper — returns transcribed text.
    // @CircuitBreaker/@Retry aktif via Spring AOP (panggilan dari luar bean,
    // bukan internal this-call). Fallback menjaga perilaku lama: return "".
    @Retry(name = "groq")
    @CircuitBreaker(name = "groq", fallbackMethod = "transcribeFallback")
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
                    // Sebelumnya .block() polos — kalau Groq menggantung, thread
                    // request ikut menggantung tanpa batas. Sekarang 30s max.
                    .timeout(Duration.ofSeconds(30))
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

    // Fallback transcribe — dipanggil CircuitBreaker saat sirkuit terbuka
    // (Groq down). Perilaku sama seperti catch lama: string kosong, bukan exception.
    // Dipanggil via Spring AOP/reflection oleh Resilience4j — bukan static call,
    // jadi @SuppressWarnings menekan warning JDT "never used locally".
    @SuppressWarnings("unused")
    private String transcribeFallback(MultipartFile audioFile, Throwable t) {
        log.warn("[Groq Whisper] Transcription skipped (circuit open): {}", t.getMessage());
        return "";
    }

    // One-shot (non-streaming) completion forced into JSON output — used for background
    // tasks like memory extraction where we need a structured, parseable result rather
    // than a token stream. Returns the raw JSON string from the assistant, or null on failure.
    @Retry(name = "groq")
    @CircuitBreaker(name = "groq", fallbackMethod = "completeJsonFallback")
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
                    // Sama seperti transcribe(): block() polos → 30s max.
                    .timeout(Duration.ofSeconds(30))
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

    // Fallback completeJson — circuit terbuka: null, sama seperti catch lama.
    // Dipanggil via Spring AOP/reflection oleh Resilience4j — bukan static call,
    // jadi @SuppressWarnings menekan warning JDT "never used locally".
    @SuppressWarnings("unused")
    private String completeJsonFallback(String systemPrompt, String userContent, Throwable t) {
        log.warn("[Groq] JSON completion skipped (circuit open): {}", t.getMessage());
        return null;
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