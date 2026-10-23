package com.enterprise.shipping.shared.api;

import com.enterprise.shipping.shared.identity.GuestSessionTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Issues 2-hour fingerprint-bound guest session tokens under the 'workflow' profile.
 *
 * Sets both a JSON Bearer token response (for fetch API calls) and SameSite=Strict HttpOnly
 * cookies so top-level browser label navigation (<a target="_blank">) is seamlessly authenticated.
 */
@RestController
@Profile("workflow")
@RequestMapping("/api/auth")
public class WorkflowAuthController {

    public static final String TOKEN_COOKIE = "shipping_guest_token";
    public static final String FINGERPRINT_COOKIE = "shipping_guest_fp";

    private final GuestSessionTokenService tokens;

    public WorkflowAuthController(GuestSessionTokenService tokens) {
        this.tokens = tokens;
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
                        "ttlSeconds", issued.ttlSeconds()
                ));
    }
}
