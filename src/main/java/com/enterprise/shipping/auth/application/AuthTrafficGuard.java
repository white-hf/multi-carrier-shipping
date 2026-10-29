package com.enterprise.shipping.auth.application;

import com.enterprise.shipping.auth.config.ShippingAuthProperties;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Single-instance limits. Saturated tables fail closed without evicting active lockouts. */
@Component
public final class AuthTrafficGuard {
    public static final class Limited extends RuntimeException {
        private final long retryAfter;
        private final String code;

        public Limited(String code, long retryAfter) {
            super(code);
            this.code = code;
            this.retryAfter = Math.max(1, retryAfter);
        }

        public long retryAfter() {
            return retryAfter;
        }

        public String code() {
            return code;
        }
    }

    private record Key(String scope, String identity) {}

    private static final class Counter {
        long expiresAt;
        long lockedUntil;
        final Deque<Long> events = new ArrayDeque<>();

        Counter(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    private final ShippingAuthProperties p;
    private final Clock clock;
    private final Map<Key, Counter> counters = new HashMap<>();
    private final Deque<Long> logins = new ArrayDeque<>();

    public AuthTrafficGuard(ShippingAuthProperties p, Clock clock) {
        this.p = p;
        this.clock = clock;
    }

    public synchronized void beforeLogin(String ip, String username) {
        long now = clock.millis();
        trim(logins, now - 1000);
        if (logins.size() >= p.loginPerSecond()) {
            throw limited("AUTH_RATE_LIMITED", logins.peekFirst() + 1000, now);
        }
        logins.addLast(now);
        for (Key key : loginKeys(ip, username)) {
            Counter counter = counter(key, p.failureWindow(), "AUTH_RATE_LIMITED", now);
            if (counter.lockedUntil > now) {
                throw limited("AUTH_RATE_LIMITED", counter.lockedUntil, now);
            }
        }
    }

    public synchronized void loginResult(String ip, String username, boolean success) {
        long now = clock.millis();
        if (success) {
            // A successful account must not erase failures from other accounts on this IP.
            counters.remove(new Key("login-user", username));
            return;
        }
        for (Key key : loginKeys(ip, username)) {
            Counter counter = counter(key, p.failureWindow(), "AUTH_RATE_LIMITED", now);
            trim(counter.events, now - p.failureWindow().toMillis());
            counter.events.addLast(now);
            counter.expiresAt = now + p.failureWindow().toMillis();
            if (counter.events.size() >= p.maxFailedAttempts()) {
                counter.lockedUntil = now + p.lockoutDuration().toMillis();
                counter.expiresAt = Math.max(counter.expiresAt, counter.lockedUntil);
            }
        }
    }

    public synchronized void business(String operation, String userId, String ip) {
        int limit =
                switch (operation) {
                    case "quotes" -> p.quotesPerMinute();
                    case "bookings" -> p.bookingsPerMinute();
                    case "labels" -> p.labelsPerMinute();
                    default -> throw new IllegalArgumentException("Unknown quota operation");
                };
        long now = clock.millis();
        var keys = List.of(new Key(operation + "-user", userId), new Key(operation + "-ip", ip));
        for (Key key : keys) {
            Counter counter = counter(key, Duration.ofMinutes(1), "API_RATE_LIMITED", now);
            trim(counter.events, now - Duration.ofMinutes(1).toMillis());
            if (counter.events.size() >= limit) {
                throw limited("API_RATE_LIMITED", counter.events.peekFirst() + 60_000, now);
            }
        }
        for (Key key : keys) {
            Counter counter = counters.get(key);
            counter.events.addLast(now);
            counter.expiresAt = now + 60_000;
        }
    }

    private Counter counter(
            Key key,
            Duration window,
            String code,
            long now) {
        counters.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        Counter existing = counters.get(key);
        if (existing != null) {
            return existing;
        }
        if (counters.size() >= p.maxEntries()) {
            long earliest =
                    counters.values().stream().mapToLong(c -> c.expiresAt).min().orElse(now + 1000);
            throw limited(code, earliest, now);
        }
        Counter created = new Counter(now + window.toMillis());
        counters.put(key, created);
        return created;
    }

    private static void trim(Deque<Long> events, long cutoff) {
        while (!events.isEmpty() && events.peekFirst() <= cutoff) {
            events.removeFirst();
        }
    }

    private static List<Key> loginKeys(String ip, String username) {
        return List.of(new Key("login-ip", ip), new Key("login-user", username));
    }

    private static Limited limited(String code, long expiresAt, long now) {
        return new Limited(code, (expiresAt - now + 999) / 1000);
    }
}
