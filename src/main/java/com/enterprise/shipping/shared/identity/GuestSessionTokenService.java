package com.enterprise.shipping.shared.identity;

import com.enterprise.shipping.shared.api.WorkflowException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/**
 * Issues and verifies stateless HMAC-SHA256 session tokens bound to a client fingerprint.
 *
 * Unifies guest and future authenticated user principals under one token format so
 * downstream controllers and services remain agnostic to how the session was established.
 */
public final class GuestSessionTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private final byte[] secret;
    private final Duration tokenTtl;
    private final Clock clock;

    public GuestSessionTokenService(String secretKey, Duration tokenTtl, Clock clock) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("A non-blank token secret is required");
        }
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("A positive token TTL is required");
        }
        this.secret = secretKey.getBytes(StandardCharsets.UTF_8);
        this.tokenTtl = tokenTtl;
        this.clock = clock;
    }

    /**
     * Derives a privacy-safe guest user ID from the client seed and signs a fingerprint-bound token.
     */
    public IssuedToken issueGuestToken(String clientId, String fingerprint) {
        if (clientId == null || !SAFE_IDENTIFIER.matcher(clientId.strip()).matches()) {
            throw new WorkflowException("INVALID_CLIENT_ID", HttpStatus.BAD_REQUEST);
        }
        if (fingerprint == null || !SAFE_IDENTIFIER.matcher(fingerprint.strip()).matches()) {
            throw new WorkflowException("INVALID_CLIENT_FINGERPRINT", HttpStatus.BAD_REQUEST);
        }

        // Derive a deterministic, non-reversible guest identity so raw client tokens are never exposed
        String derivedUserId = deriveGuestUserId(clientId.strip());
        return issueTokenForUser(derivedUserId, fingerprint.strip());
    }

    /**
     * Issues a fingerprint-bound token for a resolved user identity.
     */
    public IssuedToken issueTokenForUser(String userId, String fingerprint) {
        if (userId == null || userId.isBlank() || userId.contains("|")) {
            throw new IllegalArgumentException("Invalid user ID");
        }
        if (fingerprint == null || !SAFE_IDENTIFIER.matcher(fingerprint.strip()).matches()) {
            throw new WorkflowException("INVALID_CLIENT_FINGERPRINT", HttpStatus.BAD_REQUEST);
        }

        long expiresAtMillis = clock.millis() + tokenTtl.toMillis();
        String fpHash = fingerprintHash(fingerprint.strip());

        String rawPayload = userId + "|" + expiresAtMillis + "|" + fpHash;
        String encodedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(rawPayload.getBytes(StandardCharsets.UTF_8));

        String signature = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(hmac("token:" + encodedPayload));

        return new IssuedToken(
                encodedPayload + "." + signature,
                userId,
                Instant.ofEpochMilli(expiresAtMillis),
                tokenTtl.toSeconds()
        );
    }

    /**
     * Verifies the HMAC signature, 2-hour expiration window, and client fingerprint binding.
     */
    public Optional<String> verifyAndResolveUser(String token, String presentedFingerprint) {
        if (token == null || token.isBlank() || presentedFingerprint == null || presentedFingerprint.isBlank()) {
            return Optional.empty();
        }
        if (!SAFE_IDENTIFIER.matcher(presentedFingerprint.strip()).matches()) {
            return Optional.empty();
        }

        int dotIndex = token.indexOf('.');
        if (dotIndex <= 0 || dotIndex != token.lastIndexOf('.') || dotIndex == token.length() - 1) {
            return Optional.empty();
        }

        String encodedPayload = token.substring(0, dotIndex);
        String presentedSignature = token.substring(dotIndex + 1);

        // 1. Constant-time verification of the HMAC-SHA256 signature
        byte[] expectedSigBytes = hmac("token:" + encodedPayload);
        byte[] presentedSigBytes;
        try {
            presentedSigBytes = Base64.getUrlDecoder().decode(presentedSignature);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expectedSigBytes, presentedSigBytes)) {
            return Optional.empty();
        }

        // 2. Decode payload and check expiration and fingerprint binding
        String decodedPayload;
        try {
            decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }

        String[] parts = decodedPayload.split("\\|", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }

        String userId = parts[0];
        long expiresAtMillis;
        try {
            expiresAtMillis = Long.parseLong(parts[1]);
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }

        if (userId.isBlank() || expiresAtMillis <= clock.millis()) {
            return Optional.empty();
        }

        // 3. Constant-time comparison of the bound client fingerprint hash
        byte[] expectedFpBytes = parts[2].getBytes(StandardCharsets.UTF_8);
        byte[] actualFpBytes = fingerprintHash(presentedFingerprint.strip()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedFpBytes, actualFpBytes)) {
            return Optional.empty();
        }

        return Optional.of(userId);
    }

    public String deriveGuestUserId(String clientId) {
        String hex = HexFormat.of().formatHex(hmac("guest-id:" + clientId));
        return "guest-" + hex.substring(0, 24);
    }

    private String fingerprintHash(String fingerprint) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC calculation failed", impossible);
        }
    }

    public record IssuedToken(String token, String userId, Instant expiresAt, long ttlSeconds) {}
}
