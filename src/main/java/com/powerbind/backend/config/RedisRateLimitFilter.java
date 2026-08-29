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
// Uses sliding window counter: key = "rl:{type}:{ip}", TTL = window seconds
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimitFilter implements Filter {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${rate.limit.max-requests:30}")
    private int maxRequests;

    @Value("${rate.limit.window-seconds:60}")
    private long windowSeconds;

    // Stricter limit for auth endpoints — prevent brute force beyond lockout
    private static final int AUTH_MAX_REQUESTS = 10;
    private static final long AUTH_WINDOW_SECONDS = 60;

    // General API endpoints to rate limit
    private static final String[] RATE_LIMITED_PATHS = {
        "/api/presence",
        "/api/power"
    };

    // Auth endpoints get a stricter, separate rate limit on top of lockout logic
    private static final String[] AUTH_RATE_LIMITED_PATHS = {
        "/api/auth/login",
        "/api/auth/register",
        "/api/auth/refresh"
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        String uri = httpReq.getRequestURI();
        String ip = resolveClientIp(httpReq);

        if (isAuthPath(uri)) {
            // Auth endpoints — stricter limit, separate key prefix
            if (isRateLimited(httpRes, "rl:auth:" + ip, AUTH_MAX_REQUESTS, AUTH_WINDOW_SECONDS)) {
                return;
            }
        } else if (isGeneralPath(uri)) {
            // General sensor endpoints
            if (isRateLimited(httpRes, "rl:api:" + ip, maxRequests, windowSeconds)) {
                return;
            }
        }

        chain.doFilter(request, response);
    }

    // Core rate limit logic — returns true if request should be blocked
    private boolean isRateLimited(HttpServletResponse httpRes, String key, int limit, long windowSecs)
            throws IOException {
        try {
            Long count = redisTemplate.opsForValue().increment(key);

            if (count != null && count == 1) {
                // First request in this window — set TTL
                redisTemplate.expire(key, Duration.ofSeconds(windowSecs));
            }

            Long ttl = redisTemplate.getExpire(key);
            long resetSeconds = (ttl != null && ttl > 0) ? ttl : windowSecs;
            long remaining = Math.max(0, limit - (count != null ? count : 0));

            httpRes.setHeader("X-RateLimit-Limit", String.valueOf(limit));
            httpRes.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
            httpRes.setHeader("X-RateLimit-Reset", String.valueOf(resetSeconds));

            if (count != null && count > limit) {
                httpRes.setStatus(429);
                httpRes.setContentType("application/json");
                httpRes.setHeader("Retry-After", String.valueOf(resetSeconds));
                httpRes.getWriter().write(
                    "{\"success\":false,\"error\":\"Rate limit exceeded. Please wait " +
                    resetSeconds + " seconds.\",\"retryAfterSeconds\":" + resetSeconds + "}"
                );
                return true;
            }
        } catch (Exception e) {
            // If Redis is unavailable, fail open to avoid blocking legitimate requests
            log.warn("[RateLimit] Redis unavailable, failing open: {}", e.getMessage());
        }
        return false;
    }

    private boolean isAuthPath(String path) {
        for (String p : AUTH_RATE_LIMITED_PATHS) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    private boolean isGeneralPath(String path) {
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