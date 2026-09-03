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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (agent)")
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

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
    @DisplayName("TC-U-01 Chat without conversationId creates a new conversation and persists both messages")
    void chat_withoutConversationId_shouldCreateNewConversation_andPersistBothMessages() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(groqService.streamChat(anyList())).thenReturn(Flux.just("Hi ", "Alice"));

        Conversation created = Conversation.builder().id(UUID.randomUUID()).user(alice).title("Halo").build();
        // resolveConversation persists a new Conversation when no conversationId is supplied,
        // and touches it again after the assistant reply completes
        when(conversationRepository.save(any(Conversation.class))).thenReturn(created);

        AgentRequest.Chat request = new AgentRequest.Chat();
        request.setMessage("Halo");

        StepVerifier.create(agentService.chat("alice", request))
                .expectNext("Hi ", "Alice")
                .verifyComplete();

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(2)).save(captor.capture());

        List<ChatMessage> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("Halo", saved.get(0).getContent());
        assertEquals(alice, saved.get(0).getUser());

        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("Hi Alice", saved.get(1).getContent());
        assertEquals(alice, saved.get(1).getUser());

        // conversation created once up-front, then touched again to bump updatedAt
        verify(conversationRepository, times(2)).save(any(Conversation.class));
    }

    @Test
    @DisplayName("TC-U-02 Chat with conversationId reuses the user's own conversation")
    void chat_withConversationId_shouldReuseOwnedConversation_notCreateNewOne() {
        UUID conversationId = UUID.randomUUID();
        Conversation existing = Conversation.builder().id(conversationId).user(alice).title("Existing").build();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(conversationRepository.findByIdAndUser(conversationId, alice)).thenReturn(Optional.of(existing));
        when(groqService.streamChat(anyList())).thenReturn(Flux.just("Ok"));

        AgentRequest.Chat request = new AgentRequest.Chat();
        request.setMessage("Lanjutkan");
        request.setConversationId(conversationId.toString());

        StepVerifier.create(agentService.chat("alice", request))
                .expectNext("Ok")
                .verifyComplete();

        verify(conversationRepository, never()).save(argThat(c -> !c.getId().equals(conversationId)));
    }

    @Test
    @DisplayName("TC-U-03 Reading messages only queries the user's own conversation")
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
    @DisplayName("TC-U-04 Renaming own conversation updates the title")
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
    @DisplayName("TC-U-05 Renaming another user's conversation is rejected")
    void renameConversation_shouldThrow_whenNotOwnedByUser() {
        UUID conversationId = UUID.randomUUID();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(conversationRepository.findByIdAndUser(conversationId, alice)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> agentService.renameConversation("alice", conversationId.toString(), "Judul Baru"));
    }
}