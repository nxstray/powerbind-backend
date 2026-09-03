package com.powerbind.backend.performance;

import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.UserRepository;

import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Performance test — excluded from normal test runs via maven-surefire excludedGroups
@Tag("performance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LoginPerformanceTest {

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedUser() {
        if (userRepository.existsByUsername("perf_user")) return;
        userRepository.save(User.builder()
                .username("perf_user")
                .password(passwordEncoder.encode("password123"))
                .displayName("Performance Test User")
                .build());
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    void loginEndpoint_shouldRespondUnder500ms() {
        long start = System.currentTimeMillis();

        Response response = RestAssured.given()
                .contentType("application/json")
                .body(Map.of("username", "perf_user", "password", "password123"))
                .post("http://localhost:" + port + "/api/auth/login");

        long duration = System.currentTimeMillis() - start;

        assertEquals(200, response.getStatusCode(), "Login should actually succeed for this timing to be meaningful");
        assertTrue(duration < 500, "Login took too long: " + duration + "ms");
    }
}