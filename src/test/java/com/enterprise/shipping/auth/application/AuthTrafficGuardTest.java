package com.enterprise.shipping.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.shipping.auth.config.ShippingAuthProperties;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

class AuthTrafficGuardTest {
    private final MutableClock clock = new MutableClock();

    static ShippingAuthProperties properties(int maxEntries) {
        return new ShippingAuthProperties(
                "test-only-signing-secret-of-at-least-32-chars",
                Duration.ofHours(2),
                List.of(
                        new ShippingAuthProperties.User(
                                "alpha", "test-alpha-password", null, "user-alpha", "Alpha")),
                5,
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
                5,
                maxEntries,
                10,
                5,
                20);
    }

    @Test
    void locksAbusiveIpWithoutLockingTheAccountOnOtherIpsAndExpires() {
        var guard = new AuthTrafficGuard(properties(4096), clock);
        for (int i = 0; i < 5; i++) {
            guard.beforeLogin("ip-a", "alpha");
            guard.loginResult("ip-a", "alpha", false);
            clock.advance(Duration.ofSeconds(1));
        }
        assertThatThrownBy(() -> guard.beforeLogin("ip-a", "alpha"))
                .isInstanceOfSatisfying(
                        AuthTrafficGuard.Limited.class,
                        limited -> assertThat(limited.retryAfter()).isBetween(895L, 900L));
        assertThatThrownBy(() -> guard.beforeLogin("ip-a", "other"))
                .isInstanceOf(AuthTrafficGuard.Limited.class);
        guard.beforeLogin("ip-b", "alpha");
        clock.advance(Duration.ofMinutes(15));
        guard.beforeLogin("ip-a", "alpha");
    }

    @Test
    void successfulLoginResetsItsIpAccountFailuresButNotIpFailures() {
        var guard = new AuthTrafficGuard(properties(4096), clock);
        for (int i = 0; i < 4; i++) {
            guard.beforeLogin("ip-a", "alpha");
            guard.loginResult("ip-a", "alpha", false);
            clock.advance(Duration.ofSeconds(1));
        }
        guard.loginResult("ip-a", "alpha", true);
        guard.beforeLogin("ip-b", "alpha");
        guard.loginResult("ip-a", "other", false);
        assertThatThrownBy(() -> guard.beforeLogin("ip-a", "third"))
                .isInstanceOf(AuthTrafficGuard.Limited.class);
    }

    @Test
    void enforcesGlobalLoginSlidingWindowBeforePasswordWork() {
        var guard = new AuthTrafficGuard(properties(4096), clock);
        for (int i = 0; i < 5; i++) {
            guard.beforeLogin("ip-" + i, "user-" + i);
        }
        assertThatThrownBy(() -> guard.beforeLogin("new-ip", "new-user"))
                .isInstanceOfSatisfying(
                        AuthTrafficGuard.Limited.class,
                        limited -> assertThat(limited.retryAfter()).isEqualTo(1));
        clock.advance(Duration.ofSeconds(1));
        guard.beforeLogin("new-ip", "new-user");
    }

    @Test
    void lockedOutFloodDoesNotConsumeHealthyClientsGlobalLoginBudget() {
        var guard = new AuthTrafficGuard(properties(4096), clock);
        for (int i = 0; i < 5; i++) {
            guard.beforeLogin("abusive-ip", "alpha");
            guard.loginResult("abusive-ip", "alpha", false);
            clock.advance(Duration.ofSeconds(1));
        }
        for (int i = 0; i < 20; i++) {
            String username = "target-" + i;
            assertThatThrownBy(() -> guard.beforeLogin("abusive-ip", username))
                    .isInstanceOfSatisfying(
                            AuthTrafficGuard.Limited.class,
                            limited -> assertThat(limited.retryAfter()).isEqualTo(899));
        }
        for (int i = 0; i < 5; i++) {
            guard.beforeLogin("healthy-ip-" + i, "alpha");
        }
        assertThatThrownBy(() -> guard.beforeLogin("sixth-ip", "alpha"))
                .isInstanceOfSatisfying(
                        AuthTrafficGuard.Limited.class,
                        limited -> assertThat(limited.retryAfter()).isEqualTo(1));
    }

    @Test
    void globalRejectionsDoNotFillTheCounterTable() {
        var guard = new AuthTrafficGuard(properties(12), clock);
        for (int i = 0; i < 5; i++) {
            guard.beforeLogin("ip-" + i, "alpha");
        }
        for (int i = 0; i < 20; i++) {
            String ip = "rejected-ip-" + i;
            assertThatThrownBy(() -> guard.beforeLogin(ip, "alpha"))
                    .isInstanceOf(AuthTrafficGuard.Limited.class);
        }
        clock.advance(Duration.ofSeconds(1));
        guard.beforeLogin("new-healthy-ip", "alpha");
    }

    @Test
    void quotaCannotBeBypassedByChangingIpOrAccountAndRecoversAfterWindow() {
        var guard = new AuthTrafficGuard(properties(4096), clock);
        for (int i = 0; i < 10; i++) {
            guard.business("quotes", "user-a", "ip-a");
        }
        assertThatThrownBy(() -> guard.business("quotes", "user-a", "ip-b"))
                .isInstanceOf(AuthTrafficGuard.Limited.class);
        assertThatThrownBy(() -> guard.business("quotes", "user-b", "ip-a"))
                .isInstanceOf(AuthTrafficGuard.Limited.class);
        // Each operation owns its own budget; idempotent replays also count at admission.
        guard.business("bookings", "user-a", "ip-a");
        clock.advance(Duration.ofMinutes(1));
        guard.business("quotes", "user-a", "ip-a");
    }

    @Test
    void concurrentRequestsCannotOverrunTheSameQuota() {
        var guard = new AuthTrafficGuard(properties(4096), clock);
        long admitted =
                java.util.stream.IntStream.range(0, 32)
                        .parallel()
                        .filter(
                                index -> {
                                    try {
                                        guard.business("quotes", "same-user", "same-ip");
                                        return true;
                                    } catch (AuthTrafficGuard.Limited limited) {
                                        return false;
                                    }
                                })
                        .count();
        assertThat(admitted).isEqualTo(10);
    }

    @Test
    void saturatedCounterTableRetainsLockoutsAndFailsClosedUntilExpiry() {
        var guard = new AuthTrafficGuard(properties(4), clock);
        guard.beforeLogin("ip-a", "alpha");
        guard.beforeLogin("ip-b", "beta");
        assertThatThrownBy(() -> guard.beforeLogin("ip-c", "gamma"))
                .isInstanceOf(AuthTrafficGuard.Limited.class);
        clock.advance(Duration.ofMinutes(5));
        guard.beforeLogin("ip-c", "gamma");
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-29T12:00:00Z");

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
