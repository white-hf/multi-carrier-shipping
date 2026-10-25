package com.enterprise.shipping.auth.infrastructure;

import com.enterprise.shipping.auth.api.ShippingAuthController;
import com.enterprise.shipping.auth.application.GuestSessionTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces same-origin writes and fingerprint-bound token authentication.
 * The shared tracing filter runs first so rejected requests retain diagnostic context.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ShippingAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_USER_ATTR = "shipping.authenticatedUserId";

    private final GuestSessionTokenService tokens;
    private final boolean requireToken;

    public ShippingAuthenticationFilter(
            GuestSessionTokenService tokens,
            @Value("${shipping.auth.require-token:true}") boolean requireToken) {
        this.tokens = tokens;
        this.requireToken = requireToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String id = MDC.get("requestId");
        // 1. Reject cross-origin browser writes
        String origin = request.getHeader("Origin");
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                && origin != null
                && !sameOrigin(origin, request)) {
            writeJsonError(response, 403, "ORIGIN_NOT_ALLOWED", id);
            return;
        }

        // 2. Allow the guest token issuance endpoint to proceed after same-origin check
        if ("/api/auth/guest".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        // 3. Extract Bearer token and client fingerprint (fallback to SameSite=Strict cookies on GET)
        String token = extractBearerToken(request);
        String fingerprint = request.getHeader("X-Client-Fingerprint");
        if ("GET".equals(request.getMethod())) {
            if (token == null) {
                token = extractCookie(request, ShippingAuthController.TOKEN_COOKIE);
            }
            if (fingerprint == null || fingerprint.isBlank()) {
                fingerprint = extractCookie(request, ShippingAuthController.FINGERPRINT_COOKIE);
            }
        }

        boolean credentialsPresented = request.getHeader("Authorization") != null
                || request.getHeader("X-Client-Fingerprint") != null
                || token != null;

        if (credentialsPresented || requireToken) {
            Optional<String> verifiedUser = tokens.verifyAndResolveUser(token, fingerprint);
            if (verifiedUser.isEmpty()) {
                writeJsonError(response, 401, "UNAUTHORIZED", id);
                return;
            }
            request.setAttribute(AUTHENTICATED_USER_ATTR, verifiedUser.get());
        }

        chain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).strip();
        }
        return "";
    }

    private String extractCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void writeJsonError(HttpServletResponse response, int status, String code, String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + code.replace('_', ' ')
                        + "\",\"requestId\":\"" + requestId + "\",\"fieldErrors\":[]}"
        );
    }

    private boolean sameOrigin(String origin, HttpServletRequest request) {
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() == -1
                    ? ("https".equals(uri.getScheme()) ? 443 : 80)
                    : uri.getPort();

            return request.getScheme().equals(uri.getScheme())
                    && request.getServerName().equalsIgnoreCase(uri.getHost())
                    && request.getServerPort() == port;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
