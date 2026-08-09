package com.powerbind.backend.performance;

import io.restassured.RestAssured;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Performance test — excluded from normal test runs via maven-surefire excludedGroups
@Tag("performance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LoginPerformanceTest {

    @LocalServerPort
    private int port;

    @Test
    void loginEndpoint_shouldRespondUnder500ms() {
        long start = System.currentTimeMillis();

        RestAssured.given()
                .contentType("application/json")
                .body(Map.of("email", "perf@test.com", "password", "password123"))
                .post("http://localhost:" + port + "/api/auth/login");

        long duration = System.currentTimeMillis() - start;
        assertTrue(duration < 500, "Login took too long: " + duration + "ms");
    }
}
