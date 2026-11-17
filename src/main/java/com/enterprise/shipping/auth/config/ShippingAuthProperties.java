package com.enterprise.shipping.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

/** Account configuration has no usable public defaults. Spring also supports indexed env users. */
@ConfigurationProperties("shipping.auth")
public record ShippingAuthProperties(
        String tokenSecret,
        @DefaultValue("2h") Duration tokenTtl,
        List<User> users,
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("5m") Duration failureWindow,
        @DefaultValue("15m") Duration lockoutDuration,
        @DefaultValue("5") int loginPerSecond,
        @DefaultValue("4096") int maxEntries,
        @DefaultValue("10") int quotesPerMinute,
        @DefaultValue("5") int bookingsPerMinute,
        @DefaultValue("20") int labelsPerMinute) {

    public ShippingAuthProperties {
        if (tokenSecret == null || tokenSecret.length() < 32) {
            throw new IllegalArgumentException(
                    "Configure an auth token secret of at least 32 characters");
        }
        if (tokenTtl == null
                || tokenTtl.isNegative()
                || tokenTtl.isZero()
                || tokenTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Token TTL must be positive and at most 24 hours");
        }
        if (users == null || users.isEmpty() || users.size() > 100) {
            throw new IllegalArgumentException("Configure between 1 and 100 auth accounts");
        }
        users = List.copyOf(users);
        var names = new HashSet<String>();
        var identities = new HashSet<String>();
        for (User user : users) {
            if (!names.add(user.username()) || !identities.add(user.userId())) {
                throw new IllegalArgumentException("Auth usernames and user IDs must be unique");
            }
        }
        if (maxFailedAttempts < 1
                || loginPerSecond < 1
                || maxEntries < 4
                || quotesPerMinute < 1
                || bookingsPerMinute < 1
                || labelsPerMinute < 1
                || failureWindow == null
                || failureWindow.isNegative()
                || failureWindow.isZero()
                || lockoutDuration == null
                || lockoutDuration.isNegative()
                || lockoutDuration.isZero()) {
            throw new IllegalArgumentException("Auth limits and durations must be positive");
        }
    }

    @Override
    public String toString() {
        return "ShippingAuthProperties[credentials=REDACTED]";
    }

    public record User(
            String username,
            String password,
            String passwordHash,
            String userId,
            String displayName) {

        public User {
            if (username == null
                    || !username.matches("[A-Za-z0-9._@-]{1,64}")
                    || userId == null
                    || !userId.matches("[A-Za-z0-9._:-]{1,100}")
                    || displayName == null
                    || displayName.isBlank()
                    || displayName.length() > 100) {
                throw new IllegalArgumentException("Invalid configured auth account identity");
            }
            boolean plain = password != null && !password.isEmpty();
            boolean hashed = passwordHash != null && !passwordHash.isBlank();
            if (plain == hashed
                    || plain && (password.isBlank()
                            || password.length() < 12
                            || password.length() > 1024)) {
                throw new IllegalArgumentException(
                        "Configure a password (12–1024 chars) or a password hash");
            }
        }

        @Override
        public String toString() {
            return "User[credentials=REDACTED]";
        }
    }
}
