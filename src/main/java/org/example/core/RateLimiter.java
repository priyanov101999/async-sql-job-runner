package org.example.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(RateLimiter.class);

    private static class Bucket {
        int tokens;
        long lastSec;
        Bucket(int t, long s) { tokens = t; lastSec = s; }
    }

    private final int perMinute;
    private final ConcurrentHashMap<String, Bucket> map = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute) {
        this.perMinute = perMinute;
        LOG.info("[RateLimiter] Initialized with {} tokens per minute", perMinute);
    }

    public boolean allow(String userId) {
        long now = Instant.now().getEpochSecond();
        Bucket b = map.computeIfAbsent(userId, k -> {
            LOG.info("[RateLimiter] Creating new bucket for user {}", userId);
            return new Bucket(perMinute, now);
        });

        synchronized (b) {
            if (now - b.lastSec >= 60) {
                LOG.info("[RateLimiter] Resetting tokens for user {}. Previous tokens: {}", userId, b.tokens);
                b.tokens = perMinute;
                b.lastSec = now;
            }

            if (b.tokens <= 0) {
                LOG.warn("[RateLimiter] User {} rate limited. Tokens exhausted", userId);
                return false;
            }

            b.tokens--;
            LOG.info("[RateLimiter] Allowed request for user {}. Remaining tokens: {}", userId, b.tokens);
            return true;
        }
    }
}
