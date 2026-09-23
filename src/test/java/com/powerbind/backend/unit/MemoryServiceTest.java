package com.powerbind.backend.unit;

import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.User;
import com.powerbind.backend.model.UserMemory;
import com.powerbind.backend.repository.ChatMessageRepository;
import com.powerbind.backend.repository.UserMemoryRepository;
import com.powerbind.backend.service.GroqService;
import com.powerbind.backend.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (memory)")
@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock private UserMemoryRepository userMemoryRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private GroqService groqService;

    @InjectMocks private MemoryService memoryService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .username("admin")
                .build();
    }

    private ChatMessage chat(String role, String content) {
        return ChatMessage.builder().role(role).content(content).build();
    }

    private UserMemory memory(String content) {
        return UserMemory.builder().user(user).content(content).build();
    }

    // Stub the "already remembered" lookup with a mutable list, mirroring the repository
    // contract (newest first) while letting the service append freshly saved facts.
    private void stubExistingMemories(UserMemory... initial) {
        when(userMemoryRepository.findByUserOrderByCreatedAtDesc(user))
                .thenReturn(new ArrayList<>(List.of(initial)));
    }

    private void stubHistory(ChatMessage... messages) {
        when(chatMessageRepository.findByUserOrderByCreatedAtDesc(eq(user), any(Pageable.class)))
                .thenReturn(List.of(messages));
    }

    @Test
    @DisplayName("TC-UNIT-MEM-01 the prompt block is empty while nothing is stored")
    void buildMemoryPromptBlock_shouldReturnEmpty_whenNoMemories() {
        when(userMemoryRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of());

        assertEquals("", memoryService.buildMemoryPromptBlock(user));
    }

    @Test
    @DisplayName("TC-UNIT-MEM-02 the prompt block lists every remembered fact")
    void buildMemoryPromptBlock_shouldListStoredFacts() {
        when(userMemoryRepository.findByUserOrderByCreatedAtDesc(user))
                .thenReturn(List.of(memory("Sering pulang kerja jam 6 sore")));

        String block = memoryService.buildMemoryPromptBlock(user);

        assertTrue(block.contains("WHAT YOU REMEMBER ABOUT THIS USER"));
        assertTrue(block.contains("- Sering pulang kerja jam 6 sore"));
    }

    @Test
    @DisplayName("TC-UNIT-MEM-03 extraction is skipped when the user has no chat history")
    void extractAndSaveMemories_shouldSkip_whenHistoryEmpty() {
        stubHistory();

        memoryService.extractAndSaveMemories(user);

        verifyNoInteractions(groqService);
        verify(userMemoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-UNIT-MEM-04 new facts returned by Groq are stored for the user")
    void extractAndSaveMemories_shouldSaveNewFacts() {
        stubHistory(chat("user", "saya biasa tidur jam 10 malam"));
        stubExistingMemories();
        when(groqService.completeJson(anyString(), anyString()))
                .thenReturn("{\"memories\": [\"Biasa tidur jam 10 malam\", \"Ingin hemat listrik 20 persen\"]}");
        when(userMemoryRepository.save(any(UserMemory.class))).thenAnswer(inv -> inv.getArgument(0));

        memoryService.extractAndSaveMemories(user);

        ArgumentCaptor<UserMemory> memoryCaptor = ArgumentCaptor.forClass(UserMemory.class);
        verify(userMemoryRepository, times(2)).save(memoryCaptor.capture());
        List<UserMemory> saved = memoryCaptor.getAllValues();
        assertEquals("Biasa tidur jam 10 malam", saved.get(0).getContent());
        assertEquals("Ingin hemat listrik 20 persen", saved.get(1).getContent());
        assertEquals(user, saved.get(0).getUser());
    }

    @Test
    @DisplayName("TC-UNIT-MEM-05 a fact that is already remembered is not stored twice")
    void extractAndSaveMemories_shouldSkipDuplicates() {
        // duplicate detection is a substring match in both directions
        stubHistory(chat("user", "hemat listrik"));
        stubExistingMemories(memory("Ingin hemat listrik 20 persen"));
        when(groqService.completeJson(anyString(), anyString()))
                .thenReturn("{\"memories\": [\"hemat listrik\"]}");

        memoryService.extractAndSaveMemories(user);

        verify(userMemoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-UNIT-MEM-06 blank facts and facts longer than 300 chars are discarded")
    void extractAndSaveMemories_shouldDiscardInvalidFacts() {
        String tooLong = "x".repeat(301);
        stubHistory(chat("user", "halo"));
        stubExistingMemories();
        when(groqService.completeJson(anyString(), anyString()))
                .thenReturn("{\"memories\": [\"   \", \"" + tooLong + "\"]}");

        memoryService.extractAndSaveMemories(user);

        verify(userMemoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-UNIT-MEM-07 malformed Groq JSON is swallowed and nothing is stored")
    void extractAndSaveMemories_shouldSwallowMalformedJson() {
        stubHistory(chat("user", "halo"));
        stubExistingMemories();
        when(groqService.completeJson(anyString(), anyString())).thenReturn("sorry, no json here");

        assertDoesNotThrow(() -> memoryService.extractAndSaveMemories(user));

        verify(userMemoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-UNIT-MEM-08 a blank Groq reply stores nothing")
    void extractAndSaveMemories_shouldIgnoreBlankReply() {
        stubHistory(chat("user", "halo"));
        stubExistingMemories();
        when(groqService.completeJson(anyString(), anyString())).thenReturn("   ");

        memoryService.extractAndSaveMemories(user);

        verify(userMemoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-UNIT-MEM-09 a Groq failure never breaks the chat flow")
    void extractAndSaveMemories_shouldSwallowGroqFailure() {
        stubHistory(chat("user", "halo"));
        stubExistingMemories();
        when(groqService.completeJson(anyString(), anyString()))
                .thenThrow(new RuntimeException("groq down"));

        assertDoesNotThrow(() -> memoryService.extractAndSaveMemories(user));

        verify(userMemoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-UNIT-MEM-10 the transcript sent to Groq is bounded, chronological, and lists known facts")
    void extractAndSaveMemories_shouldSendBoundedChronologicalTranscript() {
        // the repository hands messages back newest-first, so the service must flip them
        stubHistory(chat("assistant", "jawaban baru"), chat("user", "pertanyaan lama"));
        stubExistingMemories(memory("Suka lampu redup"));
        when(groqService.completeJson(anyString(), anyString())).thenReturn("{\"memories\": []}");

        memoryService.extractAndSaveMemories(user);

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(chatMessageRepository).findByUserOrderByCreatedAtDesc(eq(user), pageCaptor.capture());
        assertEquals(0, pageCaptor.getValue().getPageNumber());
        assertEquals(60, pageCaptor.getValue().getPageSize());

        ArgumentCaptor<String> transcriptCaptor = ArgumentCaptor.forClass(String.class);
        verify(groqService).completeJson(anyString(), transcriptCaptor.capture());
        String transcript = transcriptCaptor.getValue();
        assertTrue(transcript.contains("ALREADY REMEMBERED:"));
        assertTrue(transcript.contains("- Suka lampu redup"));
        assertTrue(transcript.indexOf("user: pertanyaan lama") < transcript.indexOf("assistant: jawaban baru"));
    }

    @Test
    @DisplayName("TC-UNIT-MEM-11 stored memories are capped at 30, dropping the oldest overflow")
    void extractAndSaveMemories_shouldEnforceMemoryCap() {
        stubHistory(chat("user", "ingat ini"));
        when(groqService.completeJson(anyString(), anyString()))
                .thenReturn("{\"memories\": [\"Fakta baru\"]}");
        when(userMemoryRepository.save(any(UserMemory.class))).thenAnswer(inv -> inv.getArgument(0));

        // first lookup = already remembered (empty), second lookup (inside the cap) = 32 rows
        List<UserMemory> overflowed = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            overflowed.add(memory(String.format("fakta-%02d", 31 - i)));
        }
        when(userMemoryRepository.findByUserOrderByCreatedAtDesc(user))
                .thenReturn(new ArrayList<>())
                .thenReturn(overflowed);

        memoryService.extractAndSaveMemories(user);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserMemory>> deletedCaptor = ArgumentCaptor.forClass(List.class);
        verify(userMemoryRepository).deleteAll(deletedCaptor.capture());
        List<UserMemory> deleted = deletedCaptor.getValue();
        assertEquals(2, deleted.size());
        assertEquals("fakta-01", deleted.get(0).getContent());
        assertEquals("fakta-00", deleted.get(1).getContent());
    }
}
