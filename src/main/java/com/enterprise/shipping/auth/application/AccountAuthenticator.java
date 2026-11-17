package com.enterprise.shipping.auth.application;

import com.enterprise.shipping.auth.config.ShippingAuthProperties;

import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Salted PBKDF2 verification; nonexistent accounts perform the same slow password derivation. */
@Component
public final class AccountAuthenticator {
    private static final int ITERATIONS = 600_000;
    private final Map<String, Account> accounts;
    private final Set<String> userIds;
    private final Credential dummy;

    private record Credential(byte[] salt, byte[] hash) {}

    public record Account(String username, String userId, String displayName) {}

    private final Map<String, Credential> credentials;

    public AccountAuthenticator(ShippingAuthProperties properties) {
        var accountMap = new LinkedHashMap<String, Account>();
        var credentialMap = new LinkedHashMap<String, Credential>();
        for (var user : properties.users()) {
            accountMap.put(
                    user.username(),
                    new Account(user.username(), user.userId(), user.displayName()));
            credentialMap.put(
                    user.username(),
                    user.passwordHash() != null && !user.passwordHash().isBlank()
                            ? parse(user.passwordHash())
                            : encode(user.password()));
        }
        accounts = Map.copyOf(accountMap);
        credentials = Map.copyOf(credentialMap);
        userIds = Set.copyOf(accounts.values().stream().map(Account::userId).toList());
        dummy = encode(Base64.getEncoder().encodeToString(new SecureRandom().generateSeed(32)));
    }

    public Account authenticate(String username, String password) {
        Credential credential = credentials.getOrDefault(username, dummy);
        byte[] actual = derive(password, credential.salt());
        boolean matches = MessageDigest.isEqual(credential.hash(), actual);
        Arrays.fill(actual, (byte) 0);
        return matches ? accounts.get(username) : null;
    }

    public boolean containsUser(String userId) {
        return userIds.contains(userId);
    }

    private static Credential encode(String password) {
        byte[] salt = new SecureRandom().generateSeed(16);
        return new Credential(salt, derive(password, salt));
    }

    private static Credential parse(String encoded) {
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4
                    || !"pbkdf2-sha256".equals(parts[0])
                    || !Integer.toString(ITERATIONS).equals(parts[1])) {
                throw new IllegalArgumentException();
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] hash = Base64.getDecoder().decode(parts[3]);
            if (salt.length != 16 || hash.length != 32) {
                throw new IllegalArgumentException();
            }
            return new Credential(salt, hash);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid PBKDF2 account credential format");
        }
    }

    private static byte[] derive(String password, byte[] salt) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("PBKDF2 unavailable", impossible);
        } finally {
            spec.clearPassword();
        }
    }
}
