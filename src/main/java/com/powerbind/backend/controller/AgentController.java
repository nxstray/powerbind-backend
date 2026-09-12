package com.powerbind.backend.controller;

import com.powerbind.backend.data.ApiResponse;
import com.powerbind.backend.data.request.AgentRequest;
import com.powerbind.backend.data.response.ChatMessageResponse;
import com.powerbind.backend.data.response.ConversationResponse;
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

    // Text chat — streaming SSE, persisted into a conversation thread.
    // Long-term memory extraction also runs here, but entirely in the background —
    // there is no memory endpoint or UI, by design (see MemoryService).
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

    // Ephemeral Q&A for the Metrics page overlay — streamed but NOT persisted
    // (no conversation rows, no memory extraction) so it never appears in the
    // AgentPage history. Takes a plain JSON body { message }.
    @PostMapping(value = "/quick-ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream a one-shot AI answer without saving conversation history")
    public Flux<String> quickAsk(
            @AuthenticationPrincipal String username,
            @Valid @RequestBody AgentRequest.QuickAsk request) {
        return agentService.quickAsk(username, request.getMessage());
    }

    // Document chat — PDF/DOCX/TXT + text prompt, streaming SSE, persisted into a conversation thread
    @PostMapping(value = "/document", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream AI response for document (PDF/DOCX) + text query")
    public Flux<String> document(
            @AuthenticationPrincipal String username,
            @RequestParam("message") String message,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conversationId", required = false) String conversationId) {
        return agentService.documentChat(username, message, file, conversationId);
    }

    // List all conversations for the authenticated user, most recently updated first
    @GetMapping("/conversations")
    @Operation(summary = "List conversations for the authenticated user")
    public ResponseEntity<ApiResponse<List<ConversationResponse>>> getConversations(
            @AuthenticationPrincipal String username) {
        return ResponseEntity.ok(ApiResponse.ok(agentService.getConversations(username)));
    }

    // Fetch all messages within a single conversation
    @GetMapping("/conversations/{id}")
    @Operation(summary = "Get all messages within a conversation")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getConversationMessages(
            @AuthenticationPrincipal String username,
            @PathVariable("id") String id) {
        return ResponseEntity.ok(ApiResponse.ok(agentService.getConversationMessages(username, id)));
    }

    // Delete a conversation and all of its messages
    @DeleteMapping("/conversations/{id}")
    @Operation(summary = "Delete a conversation")
    public ResponseEntity<ApiResponse<Void>> deleteConversation(
            @AuthenticationPrincipal String username,
            @PathVariable("id") String id) {
        agentService.deleteConversation(username, id);
        return ResponseEntity.ok(ApiResponse.ok("Conversation deleted"));
    }

    // Rename a conversation
    @PatchMapping("/conversations/{id}")
    @Operation(summary = "Rename a conversation")
    public ResponseEntity<ApiResponse<ConversationResponse>> renameConversation(
            @AuthenticationPrincipal String username,
            @PathVariable("id") String id,
            @Valid @RequestBody AgentRequest.Rename request) {
        return ResponseEntity.ok(ApiResponse.ok("Conversation renamed",
                agentService.renameConversation(username, id, request.getTitle())));
    }
}