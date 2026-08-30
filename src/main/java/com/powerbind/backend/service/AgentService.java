package com.powerbind.backend.service;

import com.powerbind.backend.data.request.AgentRequest;
import com.powerbind.backend.data.response.ChatMessageResponse;
import com.powerbind.backend.global.ResourceNotFoundException;
import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.Room;
import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.ChatMessageRepository;
import com.powerbind.backend.repository.RoomRepository;
import com.powerbind.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

// Orchestrates AI agent — fetches live context, builds prompt, streams Groq response
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final GroqService groqService;
    private final InfluxDBService influxDBService;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;

    private static final double PLN_TARIFF = 1444.70;
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy HH:mm");

    // Stream text chat with live energy context — now persists per-user history
    public Flux<String> chat(String username, AgentRequest.Chat request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        chatMessageRepository.save(ChatMessage.builder()
                .user(user).role("user").content(request.getMessage()).build());

        String systemPrompt = buildSystemPrompt();
        List<Map<String, Object>> messages = buildMessages(systemPrompt, request);
        log.info("[Agent] Processing query from {}: {}", username, request.getMessage());

        StringBuilder fullReply = new StringBuilder();
        return groqService.streamChat(messages)
                .doOnNext(fullReply::append)
                .doOnComplete(() -> chatMessageRepository.save(ChatMessage.builder()
                        .user(user).role("assistant").content(fullReply.toString()).build()))
                .doOnError(e -> log.error("[Agent] Stream failed for {}: {}", username, e.getMessage()));
    }

    // Fetch full chat history for the authenticated user, oldest first
    public List<ChatMessageResponse> getHistory(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return chatMessageRepository.findByUserOrderByCreatedAtAsc(user).stream()
                .map(m -> ChatMessageResponse.builder()
                        .id(m.getId().toString())
                        .role(m.getRole())
                        .content(m.getContent())
                        .createdAt(m.getCreatedAt())
                        .build())
                .toList();
    }

    // Clear chat history for the authenticated user
    public void clearHistory(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        chatMessageRepository.deleteByUser(user);
    }

    // Stream vision chat — analyze image + energy context
    public Flux<String> visionChat(String prompt, MultipartFile imageFile) {
        try {
            byte[] bytes = imageFile.getBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);

            // Prepend energy context to vision prompt
            String enrichedPrompt = buildSystemPrompt() + "\n\nUser juga mengirimkan gambar. " + prompt;
            return groqService.streamVisionChat(enrichedPrompt, base64);
        } catch (Exception e) {
            log.error("[Agent] Vision error: {}", e.getMessage());
            return Flux.just("Maaf, gagal memproses gambar.");
        }
    }

    // Transcribe voice input via Whisper
    public String transcribe(MultipartFile audioFile) {
        return groqService.transcribe(audioFile);
    }

    // Build system prompt with live data from InfluxDB and PostgreSQL
    private String buildSystemPrompt() {
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