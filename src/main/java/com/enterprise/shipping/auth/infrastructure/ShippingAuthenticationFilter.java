package com.enterprise.shipping.auth.infrastructure;

import com.enterprise.shipping.auth.api.ShippingAuthController;
import com.enterprise.shipping.auth.application.AccountAuthenticator;
import com.enterprise.shipping.auth.application.AuthTrafficGuard;
import com.enterprise.shipping.auth.application.SessionTokenService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * Enforces same-origin writes and fingerprint-bound token authentication. The shared tracing filter
 * runs first so rejected requests retain diagnostic context.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ShippingAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_USER_ATTR = "shipping.authenticatedUserId";

    private final SessionTokenService tokens;
    private final AccountAuthenticator accounts;
    private final AuthTrafficGuard traffic;

    public ShippingAuthenticationFilter(
            SessionTokenService tokens, AccountAuthenticator accounts, AuthTrafficGuard traffic) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.traffic = traffic;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String id = MDC.get("requestId");
        // 1. Reject cross-origin browser writes
        String origin = request.getHeader("Origin");
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                && origin != null
                && !sameOrigin(origin, request)) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "ORIGIN_NOT_ALLOWED", id);
            return;
        }

        // 2. Only explicit login/logout POSTs bypass token validation
        if ("POST".equals(request.getMethod())
                && Set.of("/api/auth/login", "/api/auth/logout").contains(path(request))) {
            chain.doFilter(request, response);
            return;
        }

        // 3. Extract Bearer token and client fingerprint (fallback to SameSite=Strict cookies on
        // GET)
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

        Optional<String> verifiedUser = tokens.verifyAndResolveUser(token, fingerprint);
        if (verifiedUser.isEmpty() || !accounts.containsUser(verifiedUser.get())) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", id);
            return;
        }
        request.setAttribute(AUTHENTICATED_USER_ATTR, verifiedUser.get());

        // Count admitted requests before any database/provider work, including label downloads.
        String path = path(request);
        String operation = null;
        if ("POST".equals(request.getMethod()) && "/api/quotes".equals(path)) {
            operation = "quotes";
        } else if ("POST".equals(request.getMethod()) && "/api/bookings".equals(path)) {
            operation = "bookings";
        } else if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && path.matches("/api/bookings/[^/]+/label")) {
            operation = "labels";
        }
        try {
            if (operation != null) {
                traffic.business(operation, verifiedUser.get(), request.getRemoteAddr());
            }
        } catch (AuthTrafficGuard.Limited limited) {
            response.setHeader("Retry-After", Long.toString(limited.retryAfter()));
            writeJsonError(response, 429, limited.code(), id);
            return;
        }

        chain.doFilter(request, response);
    }

    private static String path(HttpServletRequest request) {
        // Match MVC's decoded path so percent encoding or matrix parameters cannot bypass quotas.
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
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

    private void writeJsonError(
            HttpServletResponse response,
            int status,
            String code,
            String requestId)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter()
                .write(
                        "{\"code\":\""
                                + code
                                + "\",\"message\":\""
                                + code.replace('_', ' ')
                                + "\",\"requestId\":\""
                                + requestId
                                + "\",\"fieldErrors\":[]}");
    }

    private boolean sameOrigin(String origin, HttpServletRequest request) {
        try {
            URI uri = URI.create(origin);
            int port =
                    uri.getPort() == -1
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
