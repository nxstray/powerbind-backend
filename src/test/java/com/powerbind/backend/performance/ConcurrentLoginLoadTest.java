package com.powerbind.backend.performance;

import com.powerbind.backend.model.User;
import com.powerbind.backend.repository.UserRepository;

import io.qameta.allure.Allure;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.RestAssured;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Simulates 30 family/staff members logging in at the exact same moment —
// verifies the auth endpoint holds up under concurrent load, and how fast.
@Tag("performance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConcurrentLoginLoadTest {

    private static final int CONCURRENT_USERS = 30;

    // Thresholds for "is the site still responsive under load", not just "did it error".
    // Adjust these if your target hardware is known to be slower/faster than a typical
    // dev machine — they're deliberately generous for a burst of 30 simultaneous requests
    // hitting BCrypt password hashing, which is CPU-bound by design.
    private static final long MAX_AVG_LATENCY_MS = 2_000;   // 2s average response time
    private static final long MAX_P95_LATENCY_MS = 5_000;   // 5s for the slowest 5% of requests

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

        long wallClockStart = System.currentTimeMillis();

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
        long wallClockTotalMs = System.currentTimeMillis() - wallClockStart;
        pool.shutdown();

        // ===== Latency report =====
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);

        double avgLatencyMs = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long minLatencyMs = sorted.isEmpty() ? 0 : sorted.get(0);
        long maxLatencyMs = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);
        long p95LatencyMs = percentile(sorted, 95);

        String report = buildReport(wallClockTotalMs, successCount.get(), rateLimitedCount.get(),
                unexpectedCount.get(), minLatencyMs, avgLatencyMs, p95LatencyMs, maxLatencyMs);

        // Still print to console for local runs / CI logs, AND attach to Allure so it shows
        // up in the HTML report — System.out alone is invisible in Allure's report by default,
        // it only surfaces things explicitly attached via the Allure API.
        System.out.println(report);
        Allure.addAttachment("Latency report (30 concurrent logins)", "text/plain", report, ".txt");

        // ===== Correctness: nothing should error, everything should resolve =====
        assertTrue(finished, "All 30 requests should complete within 30s");
        assertTrue(unexpectedCount.get() == 0,
                "No request should fail with an unexpected status (5xx or timeout)");
        assertTrue(successCount.get() + rateLimitedCount.get() == CONCURRENT_USERS,
                "Every request should either succeed or be gracefully rate-limited, never error");

        // ===== Performance: the site should still feel responsive under this load =====
        assertTrue(avgLatencyMs <= MAX_AVG_LATENCY_MS,
                String.format("Average login latency too high under 30 concurrent users: %.1fms (threshold %dms)",
                        avgLatencyMs, MAX_AVG_LATENCY_MS));
        assertTrue(p95LatencyMs <= MAX_P95_LATENCY_MS,
                String.format("P95 login latency too high under 30 concurrent users: %dms (threshold %dms)",
                        p95LatencyMs, MAX_P95_LATENCY_MS));
    }

    // Nearest-rank percentile over an already-sorted list
    private long percentile(List<Long> sortedLatencies, int percentile) {
        if (sortedLatencies.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sortedLatencies.size()) - 1;
        index = Math.max(0, Math.min(index, sortedLatencies.size() - 1));
        return sortedLatencies.get(index);
    }

    private String buildReport(long wallClockTotalMs, int successCount, int rateLimitedCount,
                                int unexpectedCount, long minLatencyMs, double avgLatencyMs,
                                long p95LatencyMs, long maxLatencyMs) {
        String line = "=".repeat(60);
        return String.join("\n",
                line,
                "[LoadTest] 30 concurrent logins — response time report",
                line,
                String.format("  Total wall-clock time  : %d ms", wallClockTotalMs),
                String.format("  Successful (200)       : %d", successCount),
                String.format("  Rate-limited (429)     : %d", rateLimitedCount),
                String.format("  Unexpected (5xx/error) : %d", unexpectedCount),
                String.format("  Min latency            : %d ms", minLatencyMs),
                String.format("  Avg latency            : %.1f ms  (threshold %d ms)", avgLatencyMs, MAX_AVG_LATENCY_MS),
                String.format("  P95 latency            : %d ms  (threshold %d ms)", p95LatencyMs, MAX_P95_LATENCY_MS),
                String.format("  Max latency            : %d ms", maxLatencyMs),
                line
        );
    }
}