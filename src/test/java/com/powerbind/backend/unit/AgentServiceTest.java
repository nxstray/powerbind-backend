package com.powerbind.backend.unit;

import com.powerbind.backend.data.request.AgentRequest;
import com.powerbind.backend.global.ResourceNotFoundException;
import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.Conversation;
import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.ChatMessageRepository;
import com.powerbind.backend.repository.ConversationRepository;
import com.powerbind.backend.repository.RoomRepository;
import com.powerbind.backend.repository.UserRepository;
import com.powerbind.backend.service.AgentService;
import com.powerbind.backend.service.GroqService;
import com.powerbind.backend.service.InfluxDBService;
import com.powerbind.backend.service.PrometheusService;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (agent)")
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock private PrometheusService prometheusService;
    @Mock private GroqService groqService;
    @Mock private InfluxDBService influxDBService;
    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private com.powerbind.backend.service.MemoryService memoryService;

    @InjectMocks private AgentService agentService;

    private User alice;

    @BeforeEach
    void setUp() {
        alice = User.builder().id(UUID.randomUUID()).username("alice").displayName("Alice").build();
        lenient().when(roomRepository.findAll()).thenReturn(List.of());
        lenient().when(influxDBService.queryCurrentWatts()).thenReturn(0.0);
        lenient().when(influxDBService.queryTodayKwh()).thenReturn(0.0);
        lenient().when(memoryService.buildMemoryPromptBlock(any())).thenReturn("");
    }

    @Severity(SeverityLevel.CRITICAL)
    @Test
    @DisplayName("TC-UNIT-AGENT-01 Chat without conversationId creates a new conversation and persists both messages")
    void chat_withoutConversationId_shouldCreateNewConversation_andPersistBothMessages() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(groqService.streamChat(anyList())).thenReturn(Flux.just("Hi ", "Alice"));

        Conversation created = Conversation.builder().id(UUID.randomUUID()).user(alice).title("Listrik").build();
        // resolveConversation persists a new Conversation when no conversationId is supplied,
        // and touches it again after the assistant reply completes
        when(conversationRepository.save(any(Conversation.class))).thenReturn(created);

        AgentRequest.Chat request = new AgentRequest.Chat();
        request.setMessage("Kenapa tagihan listrik naik?");

        // Normal in-scope stream: Groq chunks only, no suggestions trailer (chips are OOS/greeting only).
        StepVerifier.create(agentService.chat("alice", request))
                .expectNext("Hi ", "Alice")
                .verifyComplete();

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(2)).save(captor.capture());

        List<ChatMessage> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("Kenapa tagihan listrik naik?", saved.get(0).getContent());
        assertEquals(alice, saved.get(0).getUser());

        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("Hi Alice", saved.get(1).getContent());
        assertEquals(alice, saved.get(1).getUser());

        // conversation created once up-front, then touched again to bump updatedAt
        verify(conversationRepository, times(2)).save(any(Conversation.class));
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-02 Chat with conversationId reuses the user's own conversation")
    void chat_withConversationId_shouldReuseOwnedConversation_notCreateNewOne() {
        UUID conversationId = UUID.randomUUID();
        Conversation existing = Conversation.builder().id(conversationId).user(alice).title("Existing").build();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(conversationRepository.findByIdAndUser(conversationId, alice)).thenReturn(Optional.of(existing));
        when(groqService.streamChat(anyList())).thenReturn(Flux.just("Ok"));

        AgentRequest.Chat request = new AgentRequest.Chat();
        request.setMessage("Lanjutkan");
        request.setConversationId(conversationId.toString());

        // Normal in-scope stream: Groq chunks only, no suggestions trailer.
        StepVerifier.create(agentService.chat("alice", request))
                .expectNext("Ok")
                .verifyComplete();

        verify(conversationRepository, never()).save(argThat(c -> !c.getId().equals(conversationId)));
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-03 Reading messages only queries the user's own conversation")
    void getConversationMessages_shouldOnlyQueryMessagesForOwnedConversation() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).user(alice).title("T").build();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(conversationRepository.findByIdAndUser(conversationId, alice)).thenReturn(Optional.of(conversation));
        when(chatMessageRepository.findByConversationOrderByCreatedAtAsc(conversation)).thenReturn(List.of());

        agentService.getConversationMessages("alice", conversationId.toString());

        verify(chatMessageRepository).findByConversationOrderByCreatedAtAsc(conversation);
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-04 Renaming own conversation updates the title")
    void renameConversation_shouldUpdateTitle_whenOwnedByUser() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(conversationId).user(alice).title("Lama").build();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(conversationRepository.findByIdAndUser(conversationId, alice)).thenReturn(Optional.of(conversation));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = agentService.renameConversation("alice", conversationId.toString(), "Judul Baru");

        assertEquals("Judul Baru", result.getTitle());
        verify(conversationRepository).save(argThat(c -> c.getTitle().equals("Judul Baru")));
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-05 Renaming another user's conversation is rejected")
    void renameConversation_shouldThrow_whenNotOwnedByUser() {
        UUID conversationId = UUID.randomUUID();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(conversationRepository.findByIdAndUser(conversationId, alice)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> agentService.renameConversation("alice", conversationId.toString(), "Judul Baru"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> captureStreamChatMessages() {
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(groqService).streamChat(captor.capture());
        return captor.getValue();
    }

    private static Map<String, Object> prometheusPayload(String seriesName, long t0, long t1, double v0, double v1) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("series", List.of(Map.of(
                "name", seriesName,
                "points", List.of(Map.of("t", t0, "v", v0), Map.of("t", t1, "v", v1)))));
        return payload;
    }

    private static AgentRequest.QuickAsk quickAsk(String message, List<String> metrics, Integer hours) {
        AgentRequest.QuickAsk request = new AgentRequest.QuickAsk();
        request.setMessage(message);
        request.setMetrics(metrics);
        request.setHours(hours);
        return request;
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-06 quick-ask rejects an unknown principal before reaching Groq")
    void quickAsk_shouldThrow_whenUserUnknown() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        AgentRequest.QuickAsk request = quickAsk("halo", null, null);

        assertThrows(ResourceNotFoundException.class, () -> agentService.quickAsk("ghost", request));
        verifyNoInteractions(groqService);
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-07 quick-ask without chart context sends the raw question to the metrics persona")
    void quickAsk_shouldSendRawQuestion_whenNoChartContext() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(prometheusService.getMetricNames()).thenReturn(List.of());
        when(groqService.streamChat(anyList())).thenReturn(Flux.just("ok"));

        StepVerifier.create(agentService.quickAsk("alice", quickAsk("berapa cpu sekarang?", null, null)))
                .expectNext("ok")
                .verifyComplete();

        List<Map<String, Object>> messages = captureStreamChatMessages();
        assertEquals(2, messages.size());
        String systemPrompt = String.valueOf(messages.get(0).get("content"));
        assertTrue(systemPrompt.contains("System Metrics"));
        assertEquals("berapa cpu sekarang?", messages.get(1).get("content"));
        verify(prometheusService, never()).queryRange(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-08 quick-ask appends a live Prometheus block for the chart on screen")
    void quickAsk_shouldAppendPrometheusContext() {
        long t0 = 1_700_000_000_000L;
        long t1 = t0 + 60_000L;

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(prometheusService.getMetricNames()).thenReturn(List.of());
        when(prometheusService.queryRange("jvm_memory_used_bytes", null, "avg", 1, 60))
                .thenReturn(prometheusPayload("heap", t0, t1, 2_097_152.0, 4_194_304.0));
        when(groqService.streamChat(anyList())).thenReturn(Flux.just("ok"));

        AgentRequest.QuickAsk request = quickAsk("jelaskan memory", List.of("jvm_memory_used_bytes"), 1);

        // Answered from live numbers, no suggestions trailer on the in-scope path.
        StepVerifier.create(agentService.quickAsk("alice", request))
                .expectNext("ok")
                .verifyComplete();

        String userMessage = String.valueOf(captureStreamChatMessages().get(1).get("content"));
        assertTrue(userMessage.startsWith("jelaskan memory"));
        assertTrue(userMessage.contains("[DATA GRAFIK AKTUAL"));
        assertTrue(userMessage.contains("Rentang: 1 jam terakhir"));
        assertTrue(userMessage.contains("Metrik: jvm_memory_used_bytes"));
        assertTrue(userMessage.contains("Rentang data: 1 jam terakhir"));
        assertTrue(userMessage.contains("- Seri (garis hijau): heap"));
        assertTrue(userMessage.contains("min 2.00 MiB"));
        assertTrue(userMessage.contains("max 4.00 MiB"));
        assertTrue(userMessage.contains("Puncak (4.00 MiB) terjadi jam"));
        assertTrue(userMessage.contains("Tren sepanjang rentang: NARIK NAIK"));

        String expectedPeak = DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.of("Asia/Jakarta"))
                .format(Instant.ofEpochMilli(t1));
        assertTrue(userMessage.contains(expectedPeak));
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-09 a metric named in the question is fetched even when the dropdown shows another")
    void quickAsk_shouldFetchMetricMentionedInQuestion() {
        long t0 = 1_700_000_000_000L;

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(prometheusService.getMetricNames())
                .thenReturn(List.of("process_cpu_usage", "jvm_memory_used_bytes"));
        // only the metric the admin asked about has data; the dropdown metric stays silent
        when(prometheusService.queryRange("jvm_memory_used_bytes", null, "avg", 1, 60))
                .thenReturn(prometheusPayload("heap", t0, t0 + 60_000L, 2_097_152.0, 2_097_152.0));
        when(groqService.streamChat(anyList())).thenReturn(Flux.just("ok"));

        AgentRequest.QuickAsk request =
                quickAsk("jelaskan jvm memory used bytes", List.of("process_cpu_usage"), 1);

        StepVerifier.create(agentService.quickAsk("alice", request))
                .expectNext("ok")
                .verifyComplete();

        String userMessage = String.valueOf(captureStreamChatMessages().get(1).get("content"));
        assertTrue(userMessage.contains("Metrik: jvm_memory_used_bytes"));
        assertFalse(userMessage.contains("Metrik: process_cpu_usage"));
        assertTrue(userMessage.contains("Tren sepanjang rentang: RELATIF STABIL"));
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-10 a dead Prometheus never blocks quick-ask (answered without numbers)")
    void quickAsk_shouldDegradeGracefully_whenPrometheusFails() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(prometheusService.getMetricNames()).thenThrow(new RuntimeException("prometheus down"));
        when(prometheusService.queryRange("jvm_memory_used_bytes", null, "avg", 1, 60))
                .thenThrow(new RuntimeException("connection refused"));
        when(groqService.streamChat(anyList())).thenReturn(Flux.just("jawaban umum"));

        AgentRequest.QuickAsk request = quickAsk("jelaskan memory", List.of("jvm_memory_used_bytes"), 1);

        StepVerifier.create(agentService.quickAsk("alice", request))
                .expectNext("jawaban umum")
                .verifyComplete();

        String userMessage = String.valueOf(captureStreamChatMessages().get(1).get("content"));
        assertEquals("jelaskan memory", userMessage);
        assertFalse(userMessage.contains("[DATA GRAFIK AKTUAL"));
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-11 out-of-scope chat is refused without calling Groq, with chips")
    void chat_shouldRefuse_whenOutOfScope() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        Conversation created = Conversation.builder().id(UUID.randomUUID()).user(alice).title("siapa itu").build();
        when(conversationRepository.save(any(Conversation.class))).thenReturn(created);

        AgentRequest.Chat request = new AgentRequest.Chat();
        request.setMessage("siapa itu prabowo");

        StepVerifier.create(agentService.chat("alice", request))
                .expectNextMatches(refusal -> refusal.contains("Mohon maaf, saya tidak bisa menjawab"))
                .expectNextMatches(trailer -> trailer.contains(AgentService.SUGGESTIONS_MARKER))
                .verifyComplete();

        verifyNoInteractions(groqService);
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(2)).save(captor.capture());
        assertEquals("assistant", captor.getAllValues().get(1).getRole());
        assertFalse(captor.getAllValues().get(1).getContent().contains(AgentService.SUGGESTIONS_MARKER));
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-12 greeting is answered briefly and steered to electricity")
    void chat_shouldGreet_whenSmallTalk() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        Conversation created = Conversation.builder().id(UUID.randomUUID()).user(alice).title("halo").build();
        when(conversationRepository.save(any(Conversation.class))).thenReturn(created);

        AgentRequest.Chat request = new AgentRequest.Chat();
        request.setMessage("halo");

        StepVerifier.create(agentService.chat("alice", request))
                .expectNextMatches(greeting -> greeting.contains("kelistrikan rumahmu"))
                .expectNextMatches(trailer -> trailer.contains(AgentService.SUGGESTIONS_MARKER))
                .verifyComplete();

        verifyNoInteractions(groqService);
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-13 out-of-scope quick-ask is refused without touching Prometheus/Groq")
    void quickAsk_shouldRefuse_whenOutOfScope() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(prometheusService.getMetricNames()).thenReturn(List.of("jvm_memory_used_bytes"));

        AgentRequest.QuickAsk request = quickAsk("siapa itu prabowo", null, null);

        StepVerifier.create(agentService.quickAsk("alice", request))
                .expectNextMatches(refusal -> refusal.contains("hanya membantu memantau"))
                .expectNextMatches(trailer -> trailer.contains(AgentService.SUGGESTIONS_MARKER))
                .verifyComplete();

        verifyNoInteractions(groqService);
        verify(prometheusService, never()).queryRange(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("TC-UNIT-AGENT-14 greeting in quick-ask is steered back to the graphs")
    void quickAsk_shouldGreet_whenSmallTalk() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

        StepVerifier.create(agentService.quickAsk("alice", quickAsk("halo", null, null)))
                .expectNextMatches(greeting -> greeting.contains("grafik serta metrik"))
                .expectNextMatches(trailer -> trailer.contains(AgentService.SUGGESTIONS_MARKER))
                .verifyComplete();

        verifyNoInteractions(groqService);
        verifyNoInteractions(prometheusService);
    }
}