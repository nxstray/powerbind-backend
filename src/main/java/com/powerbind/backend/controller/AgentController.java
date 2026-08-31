package com.powerbind.backend.controller;

import com.powerbind.backend.data.ApiResponse;
import com.powerbind.backend.data.request.AgentRequest;
import com.powerbind.backend.data.response.ChatMessageResponse;
import com.powerbind.backend.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Tag(name = "Agent", description = "AI energy advisor powered by Groq")
public class AgentController {

    private final AgentService agentService;

    // Text chat — streaming SSE
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream AI energy advisor response via SSE")
    public Flux<String> chat(
            @AuthenticationPrincipal String username,
            @Valid @RequestBody AgentRequest.Chat request) {
        return agentService.chat(username, request);
    }

    // Vision chat — image + text prompt, streaming SSE
    @PostMapping(value = "/vision", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream AI response for image + text query")
    public Flux<String> vision(
            @RequestParam("prompt") String prompt,
            @RequestParam("image") MultipartFile image) {
        return agentService.visionChat(prompt, image);
    }

    // Whisper transcription — audio file to text
    @PostMapping("/transcribe")
    @Operation(summary = "Transcribe voice input via Whisper")
    public ResponseEntity<ApiResponse<Map<String, String>>> transcribe(
            @RequestParam("file") MultipartFile file) {
        String text = agentService.transcribe(file);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("text", text)));
    }

    @GetMapping("/history")
    @Operation(summary = "Get chat history for the authenticated user")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> history(
            @AuthenticationPrincipal String username) {
        return ResponseEntity.ok(ApiResponse.ok(agentService.getHistory(username)));
    }

    @DeleteMapping("/history")
    @Operation(summary = "Clear chat history for the authenticated user")
    public ResponseEntity<ApiResponse<Void>> clearHistory(
            @AuthenticationPrincipal String username) {
        agentService.clearHistory(username);
        return ResponseEntity.ok(ApiResponse.ok("History cleared"));
    }
}