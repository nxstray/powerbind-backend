package com.powerbind.backend.smoke;

import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Smoke test — verifies the application starts and health endpoint responds
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationSmokeTest {

    @LocalServerPort
    private int port;

    @Test
    void healthEndpoint_shouldReturn200() {
        int status = RestAssured.get("http://localhost:" + port + "/actuator/health")
                .getStatusCode();
        assertEquals(200, status);
    }
}
