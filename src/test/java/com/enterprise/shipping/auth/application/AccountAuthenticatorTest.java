package com.enterprise.shipping.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.shipping.auth.config.ShippingAuthProperties;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

class AccountAuthenticatorTest {
    @Test
    void rejectsInvalidCredentialsAndUnknownAccountsWithoutExposingSecrets() {
        var properties = AuthTrafficGuardTest.properties(4096);
        var accounts = new AccountAuthenticator(properties);
        assertThat(accounts.authenticate("alpha", "test-alpha-password").userId())
                .isEqualTo("user-alpha");
        assertThat(accounts.authenticate("alpha", "wrong-password")).isNull();
        assertThat(accounts.authenticate("unknown", "test-alpha-password")).isNull();
        assertThat(properties.toString())
                .doesNotContain(properties.tokenSecret(), "test-alpha-password");
        assertThat(properties.users().get(0).toString()).doesNotContain("test-alpha-password");
    }

    @Test
    void supportsPrehashedPasswordsAndRejectsMissingOrWeakPasswords() throws Exception {
        byte[] salt = new byte[16];
        var spec = new PBEKeySpec("test-prehashed-password".toCharArray(), salt, 600_000, 256);
        byte[] hash =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec)
                        .getEncoded();
        String encoded =
                "pbkdf2-sha256$600000$"
                        + Base64.getEncoder().encodeToString(salt)
                        + "$"
                        + Base64.getEncoder().encodeToString(hash);
        var base = AuthTrafficGuardTest.properties(4096);
        var user =
                new ShippingAuthProperties.User("hashed", null, encoded, "user-hashed", "Hashed");
        var properties =
                new ShippingAuthProperties(
                        base.tokenSecret(),
                        base.tokenTtl(),
                        List.of(user),
                        base.maxFailedAttempts(),
                        base.failureWindow(),
                        base.lockoutDuration(),
                        base.loginPerSecond(),
                        base.maxEntries(),
                        base.quotesPerMinute(),
                        base.bookingsPerMinute(),
                        base.labelsPerMinute());
        assertThat(
                        new AccountAuthenticator(properties)
                                .authenticate("hashed", "test-prehashed-password")
                                .userId())
                .isEqualTo("user-hashed");
        assertThatThrownBy(
                        () ->
                                new ShippingAuthProperties.User(
                                        "weak", "short", null, "user-weak", "Weak"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ShippingAuthProperties.User(
                                        "empty", "", "", "user-empty", "Empty"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
