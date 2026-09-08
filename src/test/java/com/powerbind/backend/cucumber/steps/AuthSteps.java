package com.powerbind.backend.cucumber.steps;

import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.UserRepository;
import io.cucumber.java.Before;
import io.cucumber.java.en.*;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

public class AuthSteps {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Response response;

    // Ensures every username referenced in the feature file exists before each scenario,
    // since there is no self-registration flow — accounts are always pre-seeded.
    @Before
    public void seedKnownUsers() {
        seedIfMissing("cucumber_user", "password123");
        seedIfMissing("cucumber_lockout_user", "correct-password");
    }

    private void seedIfMissing(String username, String password) {
        if (userRepository.existsByUsername(username)) return;
        userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .displayName(username)
                .build());
    }

    @When("the user logs in with username {string} and password {string}")
    public void loginUser(String username, String password) {
        response = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", username, "password", password))
                .post("http://localhost:" + port + "/api/auth/login");
    }

    @Then("the response status should be {int}")
    public void verifyStatus(int expectedStatus) {
        Assertions.assertEquals(expectedStatus, response.getStatusCode());
    }

    @Severity(SeverityLevel.CRITICAL)
    @Then("the response should contain an access token")
    public void verifyAccessToken() {
        String token = response.jsonPath().getString("data.accessToken");
        Assertions.assertNotNull(token);
        Assertions.assertFalse(token.isEmpty());
    }
}