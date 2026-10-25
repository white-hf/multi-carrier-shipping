package com.enterprise.shipping.auth.api;

import com.enterprise.shipping.auth.application.GuestSessionTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues 2-hour fingerprint-bound guest session tokens.
 *
 * Sets both a JSON Bearer token response (for fetch API calls) and SameSite=Strict HttpOnly
 * cookies so top-level browser label navigation (<a target="_blank">) is seamlessly authenticated.
 */
@RestController
@RequestMapping("/api/auth")
public class ShippingAuthController {

    public static final String TOKEN_COOKIE = "shipping_guest_token";
    public static final String FINGERPRINT_COOKIE = "shipping_guest_fp";
    private static final List<String> DEFAULT_SUPPORTED_CARRIERS = List.of(
            "Canada Post", "Purolator", "FedEx", "UPS", "DHL Express", "Canpar", "Loomis Express"
    );

    private final GuestSessionTokenService tokens;
    private final List<String> supportedCarriers;

    public ShippingAuthController(GuestSessionTokenService tokens) {
        this(tokens, DEFAULT_SUPPORTED_CARRIERS);
    }

    @Autowired
    public ShippingAuthController(
            GuestSessionTokenService tokens,
            @Value("${shipping.market.supported-carriers:Canada Post,Purolator,FedEx,UPS,DHL Express,Canpar,Loomis Express}")
            List<String> supportedCarriers) {
        this.tokens = tokens;
        List<String> cleaned = supportedCarriers == null
                ? List.of()
                : supportedCarriers.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        this.supportedCarriers = cleaned.isEmpty() ? DEFAULT_SUPPORTED_CARRIERS : cleaned;
    }

    public record GuestAuthRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9._:-]{8,128}")
            String clientId,

            @Pattern(regexp = "[A-Za-z0-9._:-]{8,128}")
            String fingerprint
    ) {}

    @PostMapping("/guest")
    public ResponseEntity<?> issueGuestToken(
            @RequestBody @Valid GuestAuthRequest body,
            @RequestHeader(value = "X-Client-Fingerprint", required = false) String headerFingerprint) {

        String fingerprint = body.fingerprint() != null && !body.fingerprint().isBlank()
                ? body.fingerprint().strip()
                : (headerFingerprint != null ? headerFingerprint.strip() : null);

        var issued = tokens.issueGuestToken(body.clientId(), fingerprint);

        // Bind SameSite=Strict HttpOnly cookies so direct label tab opens are authenticated safely
        ResponseCookie tokenCookie = ResponseCookie.from(TOKEN_COOKIE, issued.token())
                .httpOnly(true)
                .sameSite("Strict")
                .path("/api")
                .maxAge(issued.ttlSeconds())
                .build();

        ResponseCookie fpCookie = ResponseCookie.from(FINGERPRINT_COOKIE, fingerprint)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/api")
                .maxAge(issued.ttlSeconds())
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, tokenCookie.toString())
                .header(HttpHeaders.SET_COOKIE, fpCookie.toString())
                .body(Map.of(
                        "token", issued.token(),
                        "userId", issued.userId(),
                        "mode", "GUEST",
                        "expiresAt", issued.expiresAt().toString(),
                        "ttlSeconds", issued.ttlSeconds(),
                        "supportedCarriers", supportedCarriers
                ));
    }
}
