package com.fixflow.backend.security;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded single-instance protection. Edge/distributed limits are required when scaling out. */
@Component
public class AuthRateLimiter {
    private record Window(long start, int count) { }
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    public AuthRateLimiter(Clock clock) { this.clock = clock; }
    public synchronized boolean allow(String key, int limit) {
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> now - entry.getValue().start() >= 60_000);
        var window = windows.get(key);
        if (window == null) {
            if (windows.size() >= 10_000) return false;
            windows.put(key, new Window(now, 1)); return true;
        }
        if (window.count() >= limit) return false;
        windows.put(key, new Window(window.start(), window.count() + 1));
        return true;
    }
}
