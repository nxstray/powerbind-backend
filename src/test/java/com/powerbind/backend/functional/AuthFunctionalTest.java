package com.powerbind.backend.functional;

import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.UserRepository;

import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Functional tests — verify full request/response flow of auth endpoints.
// Users are pre-seeded (family accounts), there is no self-registration flow.
@DisplayName("Functional Test (auth)")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthFunctionalTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String USERNAME = "functional_user";
    private static final String PASSWORD = "password123";

    @BeforeEach
    void seedUser() {
        if (userRepository.existsByUsername(USERNAME)) return;
        userRepository.save(User.builder()
                .username(USERNAME)
                .password(passwordEncoder.encode(PASSWORD))
                .displayName("Functional Test User")
                .build());
    }

    @Severity(SeverityLevel.BLOCKER)
    @Test
    @DisplayName("TC-F-06 Login with valid credentials returns access and refresh tokens")
    void login_withValidCredentials_shouldReturnTokens() {
        var loginResponse = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", USERNAME, "password", PASSWORD))
                .post("http://localhost:" + port + "/api/auth/login");

        assertEquals(200, loginResponse.getStatusCode());
        assertNotNull(loginResponse.jsonPath().getString("data.accessToken"));
        assertNotNull(loginResponse.jsonPath().getString("data.refreshToken"));
    }

    @Test
    @DisplayName("TC-F-07 Login with invalid credentials returns 400")
    void login_withInvalidCredentials_shouldReturn400() {
        int status = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", "notexist", "password", "wrong"))
                .post("http://localhost:" + port + "/api/auth/login")
                .getStatusCode();
        assertEquals(400, status);
    }

    @Test
    @DisplayName("TC-F-08 GET /me with a valid token returns the user's profile")
    void me_withValidToken_shouldReturnProfile() {
        String token = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", USERNAME, "password", PASSWORD))
                .post("http://localhost:" + port + "/api/auth/login")
                .jsonPath().getString("data.accessToken");

        var res = RestAssured.given()
                .header("Authorization", "Bearer " + token)
                .get("http://localhost:" + port + "/api/auth/me");

        assertEquals(200, res.getStatusCode());
        assertEquals(USERNAME, res.jsonPath().getString("data.username"));
    }

    @Test
    @DisplayName("TC-F-09 Refresh with a valid refresh token returns a new token pair")
    void refresh_withValidToken_shouldReturnNewPair() {
        String refreshToken = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", USERNAME, "password", PASSWORD))
                .post("http://localhost:" + port + "/api/auth/login")
                .jsonPath().getString("data.refreshToken");

        var res = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("refreshToken", refreshToken))
                .post("http://localhost:" + port + "/api/auth/refresh");

        assertEquals(200, res.getStatusCode());
        assertNotNull(res.jsonPath().getString("data.accessToken"));
        assertNotEquals(refreshToken, res.jsonPath().getString("data.refreshToken"));
    }

    @Test
    @DisplayName("TC-F-10 Reusing an already-rotated refresh token is rejected and kills all sessions")
    void refresh_withReusedToken_shouldBeRejectedAndRevokeAllSessions() {
        String originalRefreshToken = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", USERNAME, "password", PASSWORD))
                .post("http://localhost:" + port + "/api/auth/login")
                .jsonPath().getString("data.refreshToken");

        // First refresh — legitimate, rotates the token
        String rotatedRefreshToken = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("refreshToken", originalRefreshToken))
                .post("http://localhost:" + port + "/api/auth/refresh")
                .jsonPath().getString("data.refreshToken");

        // Reusing the original (now-revoked) token — simulates a stolen token being used
        int reuseStatus = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("refreshToken", originalRefreshToken))
                .post("http://localhost:" + port + "/api/auth/refresh")
                .getStatusCode();
        assertEquals(400, reuseStatus);

        // The legitimate rotated token must ALSO be dead now — proves all sessions were killed,
        // not just the reused one
        int rotatedTokenStatus = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("refreshToken", rotatedRefreshToken))
                .post("http://localhost:" + port + "/api/auth/refresh")
                .getStatusCode();
        assertEquals(400, rotatedTokenStatus);
    }
}