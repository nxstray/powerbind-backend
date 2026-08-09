package com.powerbind.backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

// Redis-based rate limiter — persists across restarts, works in multi-instance deploy
// Uses sliding window counter: key = "rl:{ip}", TTL = window seconds
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimitFilter implements Filter {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${rate.limit.max-requests:30}")
    private int maxRequests;

    @Value("${rate.limit.window-seconds:60}")
    private long windowSeconds;

    // Only apply rate limiting to sensor data ingestion endpoints
    private static final String[] RATE_LIMITED_PATHS = {
        "/api/presence",
        "/api/power"
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        if (shouldRateLimit(httpReq.getRequestURI())) {
            String ip = resolveClientIp(httpReq);
            String key = "rl:" + ip;

            try {
                Long count = redisTemplate.opsForValue().increment(key);

                if (count != null && count == 1) {
                    // First request in this window — set TTL
                    redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
                }

                Long ttl = redisTemplate.getExpire(key);
                long resetSeconds = (ttl != null && ttl > 0) ? ttl : windowSeconds;
                long remaining = Math.max(0, maxRequests - (count != null ? count : 0));

                httpRes.setHeader("X-RateLimit-Limit", String.valueOf(maxRequests));
                httpRes.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
                httpRes.setHeader("X-RateLimit-Reset", String.valueOf(resetSeconds));

                if (count != null && count > maxRequests) {
                    httpRes.setStatus(429);
                    httpRes.setContentType("application/json");
                    httpRes.setHeader("Retry-After", String.valueOf(resetSeconds));
                    httpRes.getWriter().write(
                        "{\"success\":false,\"error\":\"Rate limit exceeded. Please wait " +
                        resetSeconds + " seconds.\",\"retryAfterSeconds\":" + resetSeconds + "}"
                    );
                    return;
                }
            } catch (Exception e) {
                // If Redis is unavailable, fail open to avoid blocking legitimate requests
                log.warn("[RateLimit] Redis unavailable, failing open: {}", e.getMessage());
            }
        }

        chain.doFilter(request, response);
    }

    private boolean shouldRateLimit(String path) {
        for (String p : RATE_LIMITED_PATHS) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
