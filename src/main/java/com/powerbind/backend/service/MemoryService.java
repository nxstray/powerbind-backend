package com.powerbind.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.User;
import com.powerbind.backend.model.UserMemory;
import com.powerbind.backend.repository.ChatMessageRepository;
import com.powerbind.backend.repository.UserMemoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Fully invisible long-term memory for the AI agent — no UI, no manual controls.
// After every chat turn, this re-reads a window of the user's ENTIRE chat history
// (across all conversations, not just the current thread) and asks Groq to summarize
// any new durable facts/preferences worth remembering. Those facts are then quietly
// injected into every future system prompt so the assistant "just knows" the user.
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final UserMemoryRepository userMemoryRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final GroqService groqService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // How much of the user's full history to re-read on every extraction pass.
    // Bounded so token cost per chat turn stays predictable regardless of how long
    // someone has been using the assistant.
    private static final int HISTORY_WINDOW = 60;

    // Cap so the memory block injected into every system prompt stays small and cheap
    private static final int MAX_MEMORIES_PER_USER = 30;

    private static final String EXTRACTION_SYSTEM_PROMPT = """
            You extract durable, reusable facts about a user from their chat history with a smart-home \
            energy-monitoring assistant. Only extract things worth remembering across future, unrelated \
            conversations: routines/schedules, room or device preferences, energy-saving goals or budget \
            targets, household composition, or explicit requests to remember something.

            Do NOT extract: one-off questions, greetings, the assistant's own answers, or anything already \
            obvious from a single data lookup (e.g. "listrik hari ini berapa?" is not a memory).

            You will be given a list of facts ALREADY remembered about this user, followed by their recent \
            chat transcript. Only return facts that are NEW — do not repeat or rephrase anything already known.

            Reply ONLY with strict JSON in this exact shape, nothing else:
            {"memories": ["short new fact 1", "short new fact 2"]}

            Each fact must be a short standalone sentence in Indonesian, under 20 words. If there is nothing \
            new worth remembering, reply {"memories": []}.
            """;

    // Build the "what I know about you" block injected into the AI's system prompt.
    // Returns an empty string when there's nothing stored yet.
    public String buildMemoryPromptBlock(User user) {
        List<UserMemory> memories = userMemoryRepository.findByUserOrderByCreatedAtDesc(user);
        if (memories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\nWHAT YOU REMEMBER ABOUT THIS USER (weave this in naturally during normal chat without\n");
        sb.append("announcing it — but if the user directly asks whether you remember them, you CAN confirm\n");
        sb.append("you do and reference a couple of these, instead of denying it):\n");
        for (UserMemory m : memories) {
            sb.append("- ").append(m.getContent()).append("\n");
        }
        return sb.toString();
    }

    // Fire-and-forget: re-read a window of the user's full chat history across every
    // conversation and ask Groq for any new durable facts. Called after each chat/document
    // reply completes; failures are logged and swallowed so they never affect the
    // user-facing conversation, and there is no UI surface for any of this.
    public void extractAndSaveMemories(User user) {
        try {
            List<ChatMessage> recent = chatMessageRepository.findByUserOrderByCreatedAtDesc(
                    user, PageRequest.of(0, HISTORY_WINDOW));
            if (recent.isEmpty()) return;

            // repository returns newest-first — flip to chronological order for the transcript
            List<ChatMessage> chronological = new ArrayList<>(recent);
            Collections.reverse(chronological);

            List<UserMemory> existing = userMemoryRepository.findByUserOrderByCreatedAtDesc(user);

            String systemPrompt = EXTRACTION_SYSTEM_PROMPT;
            String transcript = buildTranscript(chronological, existing);

            String raw = groqService.completeJson(systemPrompt, transcript);
            if (raw == null || raw.isBlank()) return;

            JsonNode root = objectMapper.readTree(raw);
            JsonNode facts = root.path("memories");
            if (!facts.isArray() || facts.isEmpty()) return;

            for (JsonNode factNode : facts) {
                String fact = factNode.asText("").trim();
                if (fact.isBlank() || fact.length() > 300) continue;
                if (isDuplicate(fact, existing)) continue;

                UserMemory saved = userMemoryRepository.save(UserMemory.builder()
                        .user(user).content(fact).build());
                existing.add(saved);
            }

            enforceCap(user);
        } catch (Exception e) {
            // Memory extraction is best-effort — never let it break the chat flow
            log.warn("[Memory] Extraction failed for {}: {}", user.getUsername(), e.getMessage());
        }
    }

    // Format existing memories + recent transcript into a single prompt payload
    private String buildTranscript(List<ChatMessage> chronological, List<UserMemory> existing) {
        StringBuilder sb = new StringBuilder();

        sb.append("ALREADY REMEMBERED:\n");
        if (existing.isEmpty()) {
            sb.append("(none yet)\n");
        } else {
            for (UserMemory m : existing) {
                sb.append("- ").append(m.getContent()).append("\n");
            }
        }

        sb.append("\nRECENT CHAT TRANSCRIPT (oldest first):\n");
        for (ChatMessage m : chronological) {
            sb.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
        }

        return sb.toString();
    }

    // Simple case-insensitive substring check — good enough to stop obvious repeats
    // without an embedding model on hand.
    private boolean isDuplicate(String fact, List<UserMemory> existing) {
        String normalized = fact.toLowerCase();
        return existing.stream().anyMatch(m -> {
            String other = m.getContent().toLowerCase();
            return other.contains(normalized) || normalized.contains(other);
        });
    }

    // Keep only the most recent MAX_MEMORIES_PER_USER — drop the oldest overflow
    private void enforceCap(User user) {
        List<UserMemory> all = userMemoryRepository.findByUserOrderByCreatedAtDesc(user);
        if (all.size() <= MAX_MEMORIES_PER_USER) return;

        List<UserMemory> overflow = all.subList(MAX_MEMORIES_PER_USER, all.size());
        userMemoryRepository.deleteAll(overflow);
    }
}