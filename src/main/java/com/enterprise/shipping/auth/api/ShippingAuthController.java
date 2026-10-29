package com.enterprise.shipping.auth.api;

import com.enterprise.shipping.auth.application.AccountAuthenticator;
import com.enterprise.shipping.auth.application.AuthTrafficGuard;
import com.enterprise.shipping.auth.application.SessionTokenService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Account login also sets Strict HttpOnly cookies for authenticated PDF tab navigation. */
@RestController
@RequestMapping("/api/auth")
public class ShippingAuthController {

    public static final String TOKEN_COOKIE = "shipping_guest_token";
    public static final String FINGERPRINT_COOKIE = "shipping_guest_fp";
    private static final List<String> DEFAULT_SUPPORTED_CARRIERS =
            List.of(
                    "Canada Post",
                    "Purolator",
                    "FedEx",
                    "UPS",
                    "DHL Express",
                    "Canpar",
                    "Loomis Express");

    private final SessionTokenService tokens;
    private final List<String> supportedCarriers;
    private final AccountAuthenticator accounts;
    private final AuthTrafficGuard traffic;

    @Autowired
    public ShippingAuthController(
            SessionTokenService tokens,
            AccountAuthenticator accounts,
            AuthTrafficGuard traffic,
            @Value(
                            "${shipping.market.supported-carriers:Canada"
                                + " Post,Purolator,FedEx,UPS,DHL Express,Canpar,Loomis Express}")
                    List<String> supportedCarriers) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.traffic = traffic;
        List<String> cleaned =
                supportedCarriers == null
                        ? List.of()
                        : supportedCarriers.stream()
                                .map(String::strip)
                                .filter(s -> !s.isEmpty())
                                .toList();
        this.supportedCarriers = cleaned.isEmpty() ? DEFAULT_SUPPORTED_CARRIERS : cleaned;
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 1024) String password,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{8,128}") String fingerprint) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody @Valid LoginRequest body, HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        traffic.beforeLogin(ip, body.username());
        var account = accounts.authenticate(body.username(), body.password());
        traffic.loginResult(ip, body.username(), account != null);
        if (account == null) {
            return ResponseEntity.status(401)
                    .body(error("INVALID_CREDENTIALS", "Invalid username or password"));
        }
        var issued = tokens.issueTokenForUser(account.userId(), body.fingerprint());
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookie(TOKEN_COOKIE, issued.token(), issued.ttlSeconds(), request))
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookie(
                                FINGERPRINT_COOKIE,
                                body.fingerprint(),
                                issued.ttlSeconds(),
                                request))
                .body(
                        Map.of(
                                "token", issued.token(),
                                "userId", issued.userId(),
                                "username", account.username(),
                                "displayName", account.displayName(),
                                "mode", "AUTHENTICATED",
                                "expiresAt", issued.expiresAt().toString(),
                                "ttlSeconds", issued.ttlSeconds(),
                                "supportedCarriers", supportedCarriers));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie(TOKEN_COOKIE, "", 0, request))
                .header(HttpHeaders.SET_COOKIE, cookie(FINGERPRINT_COOKIE, "", 0, request))
                .build();
    }

    @ExceptionHandler(AuthTrafficGuard.Limited.class)
    public ResponseEntity<?> rateLimited(AuthTrafficGuard.Limited limited) {
        return ResponseEntity.status(429)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfter()))
                .body(error(limited.code(), "Too many attempts. Please try later."));
    }

    private static Map<String, Object> error(String code, String message) {
        String requestId = MDC.get("requestId");
        return Map.of(
                "code",
                code,
                "message",
                message,
                "requestId",
                requestId == null ? "" : requestId,
                "fieldErrors",
                List.of());
    }

    private static String cookie(
            String name, String value, long ttlSeconds, HttpServletRequest request) {
        // Only container/trusted-proxy configuration may determine HTTPS; never trust raw headers.
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/api")
                .maxAge(ttlSeconds)
                .build()
                .toString();
    }
}
