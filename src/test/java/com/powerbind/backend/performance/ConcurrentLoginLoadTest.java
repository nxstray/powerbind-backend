package com.powerbind.backend.performance;

import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.UserRepository;

import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.RestAssured;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Simulates 30 family/staff members logging in at the exact same moment —
// verifies the auth endpoint holds up under concurrent load without errors.
@Tag("performance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConcurrentLoginLoadTest {

    private static final int CONCURRENT_USERS = 30;

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedUser() {
        if (userRepository.existsByUsername("load_test_user")) return;
        userRepository.save(User.builder()
                .username("load_test_user")
                .password(passwordEncoder.encode("password123"))
                .displayName("Load Test User")
                .build());
    }

    @Severity(SeverityLevel.NORMAL)
    @Test
    void thirtyConcurrentLogins_shouldAllSucceedOrRateLimitGracefully() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(CONCURRENT_USERS);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rateLimitedCount = new AtomicInteger();
        AtomicInteger unexpectedCount = new AtomicInteger();

        List<Long> latenciesMs = new CopyOnWriteArrayList<>();

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    long start = System.currentTimeMillis();

                    int status = RestAssured.given()
                            .contentType("application/json")
                            .body(Map.of("username", "load_test_user", "password", "password123"))
                            .post("http://localhost:" + port + "/api/auth/login")
                            .getStatusCode();

                    latenciesMs.add(System.currentTimeMillis() - start);

                    if (status == 200) successCount.incrementAndGet();
                    else if (status == 429) rateLimitedCount.incrementAndGet();
                    else unexpectedCount.incrementAndGet();
                } catch (Exception e) {
                    unexpectedCount.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown(); // fire all 30 requests at once
        boolean finished = doneGate.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        double avgLatency = latenciesMs.stream().mapToLong(Long::longValue).average().orElse(0);
        System.out.printf(
                "[LoadTest] 30 concurrent logins -> success=%d, rateLimited=%d, unexpected=%d, avgLatencyMs=%.1f%n",
                successCount.get(), rateLimitedCount.get(), unexpectedCount.get(), avgLatency);

        assertTrue(finished, "All 30 requests should complete within 30s");
        assertTrue(unexpectedCount.get() == 0,
                "No request should fail with an unexpected status (5xx or timeout)");
        assertTrue(successCount.get() + rateLimitedCount.get() == CONCURRENT_USERS,
                "Every request should either succeed or be gracefully rate-limited, never error");
    }
}