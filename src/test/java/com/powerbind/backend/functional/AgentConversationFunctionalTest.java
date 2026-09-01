package com.powerbind.backend.functional;

import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.Conversation;
import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.ChatMessageRepository;
import com.powerbind.backend.repository.ConversationRepository;
import com.powerbind.backend.repository.UserRepository;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Verifies /api/agent/conversations is isolated per user — one family member must never
// see, read, or delete another family member's conversation. Groq itself is not called
// here; conversations/messages are seeded directly so the test doesn't depend on an
// external API key.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgentConversationFunctionalTest {

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User alice;
    private User bob;
    private Conversation aliceConversation;
    private Conversation bobConversation;

    @BeforeEach
    void seedUsersAndConversations() {
        alice = userRepository.findByUsername("hist_alice")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("hist_alice").password(passwordEncoder.encode("password123"))
                        .displayName("Alice").build()));

        bob = userRepository.findByUsername("hist_bob")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("hist_bob").password(passwordEncoder.encode("password123"))
                        .displayName("Bob").build()));

        aliceConversation = conversationRepository.save(Conversation.builder()
                .user(alice).title("Percakapan Alice").build());
        bobConversation = conversationRepository.save(Conversation.builder()
                .user(bob).title("Percakapan Bob").build());

        chatMessageRepository.save(ChatMessage.builder()
                .user(alice).conversation(aliceConversation).role("user").content("Pesan rahasia Alice").build());
        chatMessageRepository.save(ChatMessage.builder()
                .user(bob).conversation(bobConversation).role("user").content("Pesan rahasia Bob").build());
    }

    private String loginAndGetToken(String username) {
        return RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", username, "password", "password123"))
                .post("http://localhost:" + port + "/api/auth/login")
                .jsonPath().getString("data.accessToken");
    }

    @Test
    void conversationList_shouldOnlyReturnOwnConversations() {
        String aliceToken = loginAndGetToken("hist_alice");

        var res = RestAssured.given()
                .header("Authorization", "Bearer " + aliceToken)
                .get("http://localhost:" + port + "/api/agent/conversations");

        assertEquals(200, res.getStatusCode());
        List<String> titles = res.jsonPath().getList("data.title");
        assertTrue(titles.contains("Percakapan Alice"));
        assertFalse(titles.contains("Percakapan Bob"));
    }

    @Test
    void conversationMessages_shouldOnlyReturnOwnMessages() {
        String aliceToken = loginAndGetToken("hist_alice");

        var res = RestAssured.given()
                .header("Authorization", "Bearer " + aliceToken)
                .get("http://localhost:" + port + "/api/agent/conversations/" + aliceConversation.getId());

        assertEquals(200, res.getStatusCode());
        List<String> contents = res.jsonPath().getList("data.content");
        assertTrue(contents.contains("Pesan rahasia Alice"));
    }

    @Test
    void conversationMessages_forAnotherUsersConversation_shouldBeRejected() {
        String bobToken = loginAndGetToken("hist_bob");

        int status = RestAssured.given()
                .header("Authorization", "Bearer " + bobToken)
                .get("http://localhost:" + port + "/api/agent/conversations/" + aliceConversation.getId())
                .getStatusCode();

        assertTrue(status == 404 || status == 403, "Expected 404/403, got " + status);
    }

    @Test
    void deleteConversation_shouldOnlyDeleteOwnConversation() {
        String bobToken = loginAndGetToken("hist_bob");

        int deleteStatus = RestAssured.given()
                .header("Authorization", "Bearer " + bobToken)
                .delete("http://localhost:" + port + "/api/agent/conversations/" + bobConversation.getId())
                .getStatusCode();
        assertEquals(200, deleteStatus);

        // Bob's conversation is gone...
        var bobList = RestAssured.given()
                .header("Authorization", "Bearer " + bobToken)
                .get("http://localhost:" + port + "/api/agent/conversations");
        assertTrue(bobList.jsonPath().getList("data").isEmpty());

        // ...but Alice's is untouched
        String aliceToken = loginAndGetToken("hist_alice");
        var aliceList = RestAssured.given()
                .header("Authorization", "Bearer " + aliceToken)
                .get("http://localhost:" + port + "/api/agent/conversations");
        assertFalse(aliceList.jsonPath().getList("data").isEmpty());
    }

    @Test
    void conversations_withoutToken_shouldReturn401or403() {
        int status = RestAssured.get("http://localhost:" + port + "/api/agent/conversations").getStatusCode();
        assertTrue(status == 401 || status == 403, "Expected 401/403, got " + status);
    }
}