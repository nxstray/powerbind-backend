package com.powerbind.backend.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerbind.backend.service.GroqService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

// Exercises the real WebClient pipeline against a throwaway HTTP server on localhost:
// SSE decoding, JSON extraction, multipart transcription, and the graceful fallbacks.
// No external dependency -- com.sun.net.httpserver ships with the JDK.
@DisplayName("Unit Test (groq)")
class GroqServiceTest {

    private static final String CHAT_PATH = "/chat/completions";
    private static final String AUDIO_PATH = "/audio/transcriptions";
    private static final String FALLBACK =
            "Maaf, AI Agent sedang mengalami kendala (Koneksi ke server Groq gagal).";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    private GroqService groqService;

    private final AtomicReference<String> lastChatRequest = new AtomicReference<>();
    private final AtomicReference<String> lastAudioRequest = new AtomicReference<>();
    private final AtomicReference<String> lastAudioContentType = new AtomicReference<>();

    private int chatStatus;
    private String chatContentType;
    private String chatBody;
    private int audioStatus;
    private String audioBody;

    @BeforeEach
    void setUp() throws IOException {
        chatStatus = 200;
        chatContentType = "text/event-stream";
        chatBody = sseDelta("ok");
        audioStatus = 200;
        audioBody = "{\"text\":\"halo dunia\"}";

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(CHAT_PATH, exchange -> respond(exchange, lastChatRequest, chatStatus, chatContentType, chatBody));
        server.createContext(AUDIO_PATH, exchange -> respond(exchange, lastAudioRequest, audioStatus, "application/json", audioBody));
        server.start();

        groqService = new GroqService("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
        ReflectionTestUtils.setField(groqService, "model", "test-model");
        ReflectionTestUtils.setField(groqService, "visionModel", "test-vision-model");
        ReflectionTestUtils.setField(groqService, "whisperModel", "test-whisper-model");
        ReflectionTestUtils.setField(groqService, "maxTokens", 256);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange, AtomicReference<String> capture, int status,
                         String contentType, String body) throws IOException {
        capture.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (AUDIO_PATH.equals(exchange.getRequestURI().getPath())) {
            lastAudioContentType.set(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String sseDelta(String content) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}\n\n";
    }

    private JsonNode chatRequestJson() throws Exception {
        return objectMapper.readTree(lastChatRequest.get());
    }

    private static List<Map<String, Object>> userMessages(String text) {
        return List.of(Map.of("role", "user", "content", text));
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-01 streamChat emits one element per SSE delta and drops blanks and [DONE]")
    void streamChat_shouldEmitDeltas_only() {
        chatBody = sseDelta("Hi ") + "\n\n" + sseDelta("Alice") + "data: [DONE]\n\n";

        StepVerifier.create(groqService.streamChat(userMessages("halo")))
                .expectNext("Hi ", "Alice")
                .verifyComplete();
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-02 streamChat appends the no-emoji instruction and uses the configured model")
    void streamChat_shouldAppendNoEmojiInstruction() throws Exception {
        groqService.streamChat(userMessages("halo")).collectList().block();

        JsonNode body = chatRequestJson();
        assertEquals("test-model", body.path("model").asText());
        assertEquals(256, body.path("max_tokens").asInt());
        assertTrue(body.path("stream").asBoolean());

        JsonNode messages = body.path("messages");
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("halo", messages.get(0).path("content").asText());
        assertEquals("system", messages.get(1).path("role").asText());
        assertTrue(messages.get(1).path("content").asText().contains("Jangan gunakan emoji"));
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-03 streamChat only keeps payloads that carry a textual delta")
    void streamChat_shouldSkipChunksWithoutTextualDelta() {
        chatBody = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + "data: {not valid json\n\n"
                + sseDelta("Real content") + "data: [DONE]\n\n";

        StepVerifier.create(groqService.streamChat(userMessages("halo")))
                .expectNext("Real content")
                .verifyComplete();
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-04 a Groq failure degrades into a friendly fallback instead of an error")
    void streamChat_shouldFallBack_onGroqError() {
        chatStatus = 500;
        chatBody = "{\"error\":\"boom\"}";

        StepVerifier.create(groqService.streamChat(userMessages("halo")))
                .expectNext(FALLBACK)
                .verifyComplete();
    }


    @Test
    @DisplayName("TC-UNIT-GROQ-05 streamVisionChat sends a base64 data URL with the vision model")
    void streamVisionChat_shouldSendBase64Image() throws Exception {
        chatBody = sseDelta("Ada 3 lampu menyala");

        StepVerifier.create(groqService.streamVisionChat("apa ini?", "QUJD"))
                .expectNext("Ada 3 lampu menyala")
                .verifyComplete();

        JsonNode body = chatRequestJson();
        assertEquals("test-vision-model", body.path("model").asText());
        JsonNode content = body.path("messages").get(0).path("content");
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("apa ini?", content.get(0).path("text").asText());
        assertEquals("image_url", content.get(1).path("type").asText());
        assertEquals("data:image/jpeg;base64,QUJD", content.get(1).path("image_url").path("url").asText());
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-06 streamVisionChat degrades gracefully when Groq fails")
    void streamVisionChat_shouldFallBack_onGroqError() {
        chatStatus = 503;
        chatBody = "{\"error\":\"unavailable\"}";

        StepVerifier.create(groqService.streamVisionChat("apa ini?", "QUJD"))
                .expectNext(FALLBACK)
                .verifyComplete();
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-07 completeJson returns the assistant content and asks for JSON output")
    void completeJson_shouldReturnAssistantContent() throws Exception {
        chatContentType = "application/json";
        chatBody = "{\"choices\":[{\"message\":{\"content\":\"{\\\"memories\\\":[]}\"}}]}";

        String result = groqService.completeJson("system prompt", "user content");

        assertEquals("{\"memories\":[]}", result);

        JsonNode body = chatRequestJson();
        assertEquals("test-model", body.path("model").asText());
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertEquals(0.2, body.path("temperature").asDouble());
        assertFalse(body.path("stream").asBoolean());
        assertEquals("system prompt", body.path("messages").get(0).path("content").asText());
        assertEquals("user content", body.path("messages").get(1).path("content").asText());
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-08 completeJson returns null when the reply has no textual content")
    void completeJson_shouldReturnNull_whenContentMissing() {
        chatContentType = "application/json";
        chatBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}";

        assertNull(groqService.completeJson("system prompt", "user content"));
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-09 completeJson returns null when Groq responds with an error status")
    void completeJson_shouldReturnNull_onGroqError() {
        chatStatus = 429;
        chatContentType = "application/json";
        chatBody = "{\"error\":{\"message\":\"rate limited\"}}";

        assertNull(groqService.completeJson("system prompt", "user content"));
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-10 transcribe posts the audio as multipart form data and returns the transcript")
    void transcribe_shouldReturnTranscript_andPostMultipart() {
        audioBody = "{\"text\":\"nyalakan lampu kamar\"}";
        MockMultipartFile audio = new MockMultipartFile(
                "file", "voice.webm", "audio/webm", "fake-audio".getBytes(StandardCharsets.UTF_8));

        String result = groqService.transcribe(audio);

        assertEquals("nyalakan lampu kamar", result);
        assertTrue(lastAudioContentType.get().startsWith("multipart/form-data"));
        String form = lastAudioRequest.get();
        assertTrue(form.contains("test-whisper-model"));
        assertTrue(form.contains("voice.webm"));
        assertTrue(form.contains("name=\"language\""));
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-11 transcribe returns an empty string when Groq fails")
    void transcribe_shouldReturnEmpty_onGroqError() {
        audioStatus = 500;
        audioBody = "{\"error\":\"boom\"}";
        MockMultipartFile audio = new MockMultipartFile(
                "file", "voice.webm", "audio/webm", "fake-audio".getBytes(StandardCharsets.UTF_8));

        assertEquals("", groqService.transcribe(audio));
    }

    @Test
    @DisplayName("TC-UNIT-GROQ-12 transcribe returns an empty string when the payload has no text field")
    void transcribe_shouldReturnEmpty_whenTextMissing() {
        audioBody = "{\"foo\":\"bar\"}";
        MockMultipartFile audio = new MockMultipartFile(
                "file", "voice.webm", "audio/webm", "fake-audio".getBytes(StandardCharsets.UTF_8));

        assertEquals("", groqService.transcribe(audio));
    }
}

