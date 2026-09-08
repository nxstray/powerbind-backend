package com.powerbind.backend.cucumber.steps;

import com.powerbind.backend.model.ChatMessage;
import com.powerbind.backend.model.Conversation;
import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.ChatMessageRepository;
import com.powerbind.backend.repository.ConversationRepository;
import com.powerbind.backend.repository.UserRepository;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.*;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// BDD coverage for /api/agent/conversations — a family member must only ever see, read, or
// delete their own conversation threads. Groq is never called here (chat/document/vision
// stream from an external API), so scenarios seed conversations/messages directly via the
// repositories instead of driving a real chat turn.
public class AgentSteps {

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String DEFAULT_PASSWORD = "password123";

    // "owner:title" -> conversation id, so later steps can reference a seeded conversation by
    // its human-readable title instead of the DB-generated UUID
    private final Map<String, String> conversationIdByTitle = new HashMap<>();
    private Response agentResponse;

    @Given("the following conversations exist:")
    public void theFollowingConversationsExist(DataTable table) {
        for (Map<String, String> row : table.asMaps()) {
            String owner = row.get("owner");
            String title = row.get("title");
            String message = row.get("message");

            User user = userRepository.findByUsername(owner)
                    .orElseGet(() -> userRepository.save(User.builder()
                            .username(owner).password(passwordEncoder.encode(DEFAULT_PASSWORD))
                            .displayName(owner).build()));

            // Start clean so re-running this scenario doesn't accumulate duplicate conversations
            conversationRepository.findByUserOrderByUpdatedAtDesc(user).stream()
                    .filter(c -> c.getTitle().equals(title))
                    .forEach(c -> {
                        chatMessageRepository.deleteByConversation(c);
                        conversationRepository.delete(c);
                    });

            Conversation conversation = conversationRepository.save(Conversation.builder()
                    .user(user).title(title).build());
            chatMessageRepository.save(ChatMessage.builder()
                    .user(user).conversation(conversation).role("user").content(message).build());

            conversationIdByTitle.put(owner + ":" + title, conversation.getId().toString());
        }
    }

    private String loginAndGetToken(String username) {
        return RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", username, "password", DEFAULT_PASSWORD))
                .post("http://localhost:" + port + "/api/auth/login")
                .jsonPath().getString("data.accessToken");
    }

    @When("{string} requests their conversation list")
    public void requestsConversationList(String username) {
        String token = loginAndGetToken(username);
        agentResponse = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .get("http://localhost:" + port + "/api/agent/conversations");
    }

    @When("{string} opens their conversation {string}")
    public void opensOwnConversation(String username, String title) {
        String token = loginAndGetToken(username);
        String conversationId = conversationIdByTitle.get(username + ":" + title);
        agentResponse = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .get("http://localhost:" + port + "/api/agent/conversations/" + conversationId);
    }

    @When("{string} attempts to open {string}'s conversation {string}")
    public void attemptsToOpenAnothersConversation(String requester, String owner, String title) {
        String token = loginAndGetToken(requester);
        String conversationId = conversationIdByTitle.get(owner + ":" + title);
        agentResponse = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .get("http://localhost:" + port + "/api/agent/conversations/" + conversationId);
    }

    @When("{string} deletes their conversation {string}")
    public void deletesOwnConversation(String username, String title) {
        String token = loginAndGetToken(username);
        String conversationId = conversationIdByTitle.get(username + ":" + title);
        agentResponse = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .delete("http://localhost:" + port + "/api/agent/conversations/" + conversationId);
    }

    @When("an anonymous user requests the conversation list")
    public void anonymousRequestsConversationList() {
        agentResponse = RestAssured.get("http://localhost:" + port + "/api/agent/conversations");
    }

    // Named "agent response status" (not just "response status") to avoid colliding with
    // AuthSteps' identically-shaped step for /api/auth/login — different classes checking
    // different Response objects need distinct step text, otherwise Cucumber would route
    // to whichever definition it finds first and silently check the wrong response.
    @Then("the agent response status should be {int}")
    public void verifyAgentResponseStatus(int expectedStatus) {
        Assertions.assertEquals(expectedStatus, agentResponse.getStatusCode());
    }

    @Then("the agent response status should be {int} or {int}")
    public void verifyAgentResponseStatusEither(int optionA, int optionB) {
        int actual = agentResponse.getStatusCode();
        Assertions.assertTrue(actual == optionA || actual == optionB,
                "Expected " + optionA + " or " + optionB + ", got " + actual);
    }

    @Then("the conversation titles should include {string}")
    public void conversationTitlesShouldInclude(String title) {
        List<String> titles = agentResponse.jsonPath().getList("data.title");
        Assertions.assertTrue(titles.contains(title), "Expected titles to include \"" + title + "\", got " + titles);
    }

    @Then("the conversation titles should not include {string}")
    public void conversationTitlesShouldNotInclude(String title) {
        List<String> titles = agentResponse.jsonPath().getList("data.title");
        Assertions.assertFalse(titles.contains(title), "Expected titles NOT to include \"" + title + "\", got " + titles);
    }

    @Then("the conversation messages should include {string}")
    public void conversationMessagesShouldInclude(String content) {
        List<String> contents = agentResponse.jsonPath().getList("data.content");
        Assertions.assertTrue(contents.contains(content), "Expected messages to include \"" + content + "\", got " + contents);
    }
}