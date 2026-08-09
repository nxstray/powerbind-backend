package com.powerbind.backend.functional;

import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Functional tests — verify full request/response flow of auth endpoints
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthFunctionalTest {

    @LocalServerPort
    private int port;

    @Test
    void register_thenLogin_shouldReturnTokens() {
        String email = "functional_" + System.currentTimeMillis() + "@test.com";

        // Register
        int registerStatus = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("email", email, "password", "password123", "displayName", "Test"))
                .post("http://localhost:" + port + "/api/auth/register")
                .getStatusCode();
        assertEquals(200, registerStatus);

        // Login
        var loginResponse = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("email", email, "password", "password123"))
                .post("http://localhost:" + port + "/api/auth/login");

        assertEquals(200, loginResponse.getStatusCode());
        assertNotNull(loginResponse.jsonPath().getString("data.accessToken"));
        assertNotNull(loginResponse.jsonPath().getString("data.refreshToken"));
    }

    @Test
    void login_withInvalidCredentials_shouldReturn400() {
        int status = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("email", "notexist@test.com", "password", "wrong"))
                .post("http://localhost:" + port + "/api/auth/login")
                .getStatusCode();
        assertEquals(400, status);
    }
}
