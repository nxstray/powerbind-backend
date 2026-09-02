package com.powerbind.backend.service;

import com.powerbind.backend.data.request.AgentRequest;
import com.powerbind.backend.data.response.ChatMessageResponse;
import com.powerbind.backend.data.response.ConversationResponse;
import com.powerbind.backend.global.ResourceNotFoundException;
import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.Conversation;
import com.powerbind.backend.model.Room;
import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.ChatMessageRepository;
import com.powerbind.backend.repository.ConversationRepository;
import com.powerbind.backend.repository.RoomRepository;
import com.powerbind.backend.repository.UserRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Orchestrates AI agent — fetches live context, builds prompt, streams Groq response
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final GroqService groqService;
    private final InfluxDBService influxDBService;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final DocumentService documentService;
    private final ChatMessageRepository chatMessageRepository;
    private final ConversationRepository conversationRepository;
    private final MemoryService memoryService;

    private static final double PLN_TARIFF = 1444.70;
    private static final int TITLE_MAX_LENGTH = 50;
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy HH:mm");

    // Stream text chat with live energy context — persists into a conversation thread
    // and triggers background extraction of any durable facts worth remembering
    public Flux<String> chat(String username, AgentRequest.Chat request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Conversation conversation = resolveConversation(user, request.getConversationId(), request.getMessage());

        chatMessageRepository.save(ChatMessage.builder()
                .user(user).conversation(conversation).role("user").content(request.getMessage()).build());

        String systemPrompt = buildSystemPrompt(user);
        List<Map<String, Object>> messages = buildMessages(systemPrompt, request);
        log.info("[Agent] Processing query from {}: {}", username, request.getMessage());

        StringBuilder fullReply = new StringBuilder();
        return groqService.streamChat(messages)
                .doOnNext(fullReply::append)
                .doOnComplete(() -> {
                    chatMessageRepository.save(ChatMessage.builder()
                            .user(user).conversation(conversation).role("assistant").content(fullReply.toString()).build());
                    // touch the conversation so updatedAt bumps and it re-sorts to the top of the dropdown
                    conversationRepository.save(conversation);
                    extractMemoriesInBackground(user);
                })
                .doOnError(e -> log.error("[Agent] Stream failed for {}: {}", username, e.getMessage()));
    }

    // List all conversations for the authenticated user, most recently updated first
    public List<ConversationResponse> getConversations(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return conversationRepository.findByUserOrderByUpdatedAtDesc(user).stream()
                .map(c -> ConversationResponse.builder()
                        .id(c.getId().toString())
                        .title(c.getTitle())
                        .createdAt(c.getCreatedAt())
                        .updatedAt(c.getUpdatedAt())
                        .build())
                .toList();
    }

    // Fetch all messages within a single conversation, oldest first — ownership verified
    public List<ChatMessageResponse> getConversationMessages(String username, String conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Conversation conversation = findOwnedConversation(user, conversationId);

        return chatMessageRepository.findByConversationOrderByCreatedAtAsc(conversation).stream()
                .map(m -> ChatMessageResponse.builder()
                        .id(m.getId().toString())
                        .role(m.getRole())
                        .content(m.getContent())
                        .createdAt(m.getCreatedAt())
                        .build())
                .toList();
    }

    // Delete a conversation (and its messages, via cascading FK) — ownership verified
    @Transactional
    public void deleteConversation(String username, String conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Conversation conversation = findOwnedConversation(user, conversationId);
        // Delete messages explicitly first — don't rely solely on the DB's ON DELETE CASCADE
        chatMessageRepository.deleteByConversation(conversation);
        conversationRepository.delete(conversation);
    }

    // Stream vision chat — analyze image + energy context (not persisted into a conversation thread)
    public Flux<String> visionChat(String prompt, MultipartFile imageFile) {
        try {
            byte[] bytes = imageFile.getBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);

            // Prepend energy context to vision prompt
            String enrichedPrompt = buildSystemPrompt(null) + "\n\nUser juga mengirimkan gambar. " + prompt;
            return groqService.streamVisionChat(enrichedPrompt, base64);
        } catch (Exception e) {
            log.error("[Agent] Vision error: {}", e.getMessage());
            return Flux.just("Maaf, gagal memproses gambar.");
        }
    }

    // Chat with document context — extracts text from PDF/DOCX and injects into prompt, persists thread
    public Flux<String> documentChat(String username, String userMessage, MultipartFile documentFile, String conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String extractedText = documentService.extractText(documentFile);

        if (extractedText.isBlank()) {
            return Flux.just("Maaf, saya tidak bisa membaca isi dokumen ini. Pastikan formatnya PDF, DOCX, atau TXT.");
        }

        String fallbackTitle = "Dokumen: " + documentFile.getOriginalFilename();
        Conversation conversation = resolveConversation(user, conversationId,
                (userMessage != null && !userMessage.isBlank()) ? userMessage : fallbackTitle);

        chatMessageRepository.save(ChatMessage.builder()
                .user(user).conversation(conversation).role("user").content(userMessage).build());

        String systemPrompt = buildSystemPrompt(user);
        systemPrompt += "\n\n=== UPLOADED DOCUMENT: " + documentFile.getOriginalFilename() + " ===\n";
        systemPrompt += extractedText;
        systemPrompt += "\n=== END OF DOCUMENT ===\n";
        systemPrompt += "\nAnswer the user's question using the document content above when relevant.";

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));

        log.info("[Agent] Document query on file: {}", documentFile.getOriginalFilename());

        StringBuilder fullReply = new StringBuilder();
        return groqService.streamChat(messages)
                .doOnNext(fullReply::append)
                .doOnComplete(() -> {
                    chatMessageRepository.save(ChatMessage.builder()
                            .user(user).conversation(conversation).role("assistant").content(fullReply.toString()).build());
                    conversationRepository.save(conversation);
                    extractMemoriesInBackground(user);
                })
                .doOnError(e -> log.error("[Agent] Document stream failed for {}: {}", username, e.getMessage()));
    }

    // Transcribe voice input via Whisper
    public String transcribe(MultipartFile audioFile) {
        return groqService.transcribe(audioFile);
    }

    // Run memory extraction off the streaming thread so it never delays or breaks the
    // SSE response. This re-reads the user's full chat history (not just this turn) —
    // fully invisible, no UI surface — errors are already caught/logged inside MemoryService.
    private void extractMemoriesInBackground(User user) {
        Mono.fromRunnable(() -> memoryService.extractAndSaveMemories(user))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    // Resolve an existing conversation (verifying ownership) or create a new one titled from the first message
    private Conversation resolveConversation(User user, String conversationId, String firstMessage) {
        if (conversationId != null && !conversationId.isBlank()) {
            return findOwnedConversation(user, conversationId);
        }

        String source = firstMessage == null ? "" : firstMessage.trim();
        String title = source.length() > TITLE_MAX_LENGTH
                ? source.substring(0, TITLE_MAX_LENGTH).trim() + "..."
                : source;
        if (title.isBlank()) {
            title = "Percakapan Baru";
        }

        return conversationRepository.save(Conversation.builder()
                .user(user)
                .title(title)
                .build());
    }

    // Look up a conversation by id, ensuring it belongs to the given user
    private Conversation findOwnedConversation(User user, String conversationId) {
        UUID id;
        try {
            id = UUID.fromString(conversationId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResourceNotFoundException("Conversation not found");
        }
        return conversationRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
    }

    // Build system prompt with live data from InfluxDB and PostgreSQL, plus anything
    // remembered long-term about this user. Pass null when there's no authenticated
    // user in scope (e.g. vision chat currently has no per-user persistence).
    private String buildSystemPrompt(User user) {
        double currentWatts = influxDBService.queryCurrentWatts();
        double todayKwh = influxDBService.queryTodayKwh();
        double estimatedCost = todayKwh * PLN_TARIFF;
        List<Room> rooms = roomRepository.findAll();
        String now = LocalDateTime.now().format(FORMATTER);

        StringBuilder sb = new StringBuilder();
        sb.append("You are Powerbind AI, an intelligent energy advisor for a smart home system in Indonesia. ");
        sb.append("You help users understand electricity usage, identify waste, and optimize energy consumption. ");
        sb.append("Always respond in the same language the user uses (Indonesian or English). ");
        sb.append("Be concise, practical, and proactive about energy-saving recommendations.\n\n");

        sb.append("=== LIVE SYSTEM DATA (").append(now).append(") ===\n\n");

        sb.append("POWER MONITORING:\n");
        sb.append("- Current power draw: ").append(String.format("%.1f", currentWatts)).append(" W\n");
        sb.append("- Today's consumption: ").append(String.format("%.2f", todayKwh)).append(" kWh\n");
        sb.append("- Estimated cost today: Rp ").append(String.format("%.0f", estimatedCost)).append("\n");
        sb.append("- PLN tariff: Rp ").append(PLN_TARIFF).append("/kWh (R1 900VA household)\n");
        sb.append("- Monthly estimate: Rp ").append(String.format("%.0f", estimatedCost * 30)).append("\n\n");

        sb.append("ROOM STATUS:\n");
        long occupiedCount = rooms.stream().filter(Room::isPresenceDetected).count();
        long activeDevices = rooms.stream().filter(Room::isRelayOn).count();
        sb.append("- Total rooms: ").append(rooms.size()).append("\n");
        sb.append("- Occupied rooms: ").append(occupiedCount).append("\n");
        sb.append("- Active devices (relay ON): ").append(activeDevices).append("\n\n");

        for (Room room : rooms) {
            sb.append("  [").append(room.getName()).append("]\n");
            sb.append("    Presence: ").append(room.isPresenceDetected() ? "DETECTED" : "EMPTY").append("\n");
            sb.append("    Relay: ").append(room.isRelayOn() ? "ON" : "OFF").append("\n");
            // Flag waste anomaly — relay on but no presence
            if (room.isRelayOn() && !room.isPresenceDetected()) {
                sb.append("    ⚠ WARNING: Device ON but room is EMPTY — potential energy waste!\n");
            }
        }

        sb.append("\nANALYSIS GUIDELINES:\n");
        sb.append("- Flag rooms where relay is ON but no presence as energy waste\n");
        sb.append("- A typical Indonesian household uses 200-500 kWh/month\n");
        sb.append("- Suggest specific actions when anomalies are detected\n");
        sb.append("- Always give cost in Rupiah referencing PLN tariff\n");
        sb.append("- If the user asks whether you remember them or past conversations: you do NOT keep a\n");
        sb.append("  transcript of old messages, but you may have a few remembered facts about them below.\n");
        sb.append("  If that section is non-empty, say naturally that you remember a few things about them\n");
        sb.append("  (referencing one or two, not a raw dump). If it's empty, say you don't have anything\n");
        sb.append("  specific saved about them yet — don't give a flat, scripted denial either way.\n");

        if (user != null) {
            sb.append(memoryService.buildMemoryPromptBlock(user));
        }

        return sb.toString();
    }

    // Assemble message list: system prompt + conversation history + current message
    private List<Map<String, Object>> buildMessages(String systemPrompt, AgentRequest.Chat request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        if (request.getHistory() != null) {
            for (AgentRequest.Turn turn : request.getHistory()) {
                messages.add(Map.of("role", turn.getRole(), "content", turn.getContent()));
            }
        }

        messages.add(Map.of("role", "user", "content", request.getMessage()));
        return messages;
    }
}