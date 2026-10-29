package com.enterprise.shipping.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.shipping.auth.application.AccountAuthenticator;
import com.enterprise.shipping.auth.application.AuthTrafficGuard;
import com.enterprise.shipping.auth.application.SessionTokenService;
import com.enterprise.shipping.auth.config.ShippingAuthProperties;
import com.enterprise.shipping.shared.api.ShippingRequestTracingFilter;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

class ShippingAuthenticationFilterTest {
    private final SessionTokenService tokens =
            new SessionTokenService("test-secret", Duration.ofHours(2), Clock.systemUTC());
    private final ShippingAuthProperties properties =
            new ShippingAuthProperties(
                    "test-only-secret-at-least-32-characters",
                    Duration.ofHours(2),
                    List.of(
                            new ShippingAuthProperties.User(
                                    "test", "test-password-123", null, "test-user", "Test")),
                    5,
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(15),
                    5,
                    4096,
                    10,
                    5,
                    20);
    private final ShippingAuthenticationFilter auth =
            new ShippingAuthenticationFilter(
                    tokens,
                    new AccountAuthenticator(properties),
                    new AuthTrafficGuard(properties, Clock.systemUTC()));
    private final ShippingRequestTracingFilter tracing = new ShippingRequestTracingFilter();

    @Test
    void authenticationRejectionRetainsCorrelationAndClearsMdc() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/quotes");
        var response = new MockHttpServletResponse();
        tracing.doFilter(
                request,
                response,
                (req, res) ->
                        auth.doFilter(
                                req,
                                res,
                                (ignoredRequest, ignoredResponse) -> {
                                    throw new AssertionError(
                                            "Unauthenticated call reached application");
                                }));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        assertThat(response.getContentAsString()).contains(response.getHeader("X-Request-Id"));
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void loginRemainsProtectedAgainstCrossOriginWrites() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("Origin", "https://untrusted.example");
        var response = new MockHttpServletResponse();
        tracing.doFilter(
                request,
                response,
                (req, res) ->
                        auth.doFilter(
                                req,
                                res,
                                (ignoredRequest, ignoredResponse) -> {
                                    throw new AssertionError("Cross-origin request was accepted");
                                }));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("ORIGIN_NOT_ALLOWED", response.getHeader("X-Request-Id"));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void verifiedIdentityAndTracingReachApplicationTogether() throws Exception {
        String fingerprint = "fingerprint-test-123";
        var issued = tokens.issueTokenForUser("test-user", fingerprint);
        var request = new MockHttpServletRequest("POST", "/api/quotes");
        request.addHeader("Authorization", "Bearer " + issued.token());
        request.addHeader("X-Client-Fingerprint", fingerprint);
        var response = new MockHttpServletResponse();
        tracing.doFilter(
                request,
                response,
                (req, res) ->
                        auth.doFilter(
                                req,
                                res,
                                (verifiedRequest, verifiedResponse) -> {
                                    assertThat(
                                                    verifiedRequest.getAttribute(
                                                            ShippingAuthenticationFilter
                                                                    .AUTHENTICATED_USER_ATTR))
                                            .isEqualTo("test-user");
                                    assertThat(MDC.get("requestId"))
                                            .isEqualTo(response.getHeader("X-Request-Id"));
                                }));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void guestEndpointAndUnknownPrincipalsCannotReachApplication() throws Exception {
        for (String path : List.of("/api/auth/guest", "/%61pi/quotes")) {
            var request = new MockHttpServletRequest("POST", path);
            var response = new MockHttpServletResponse();
            auth.doFilter(
                    request,
                    response,
                    (req, res) -> {
                        throw new AssertionError("Anonymous request reached application");
                    });
            assertThat(response.getStatus()).isEqualTo(401);
        }
        var request = new MockHttpServletRequest("POST", "/api/quotes");
        request.addHeader(
                "Authorization",
                "Bearer "
                        + tokens.issueTokenForUser("removed-user", "fingerprint-test-123").token());
        request.addHeader("X-Client-Fingerprint", "fingerprint-test-123");
        var response = new MockHttpServletResponse();
        auth.doFilter(
                request,
                response,
                (req, res) -> {
                    throw new AssertionError("Removed account reached application");
                });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void quotaIncludesEncodedPathsAndDoesNotTrustForwardedIpHeaders() throws Exception {
        var reached = new java.util.concurrent.atomic.AtomicInteger();
        String fingerprint = "fingerprint-test-123";
        String token = tokens.issueTokenForUser("test-user", fingerprint).token();
        for (int i = 0; i < 11; i++) {
            var request =
                    new MockHttpServletRequest(
                            "POST", i % 2 == 0 ? "/api/quotes" : "/api/%71uotes;ignored=1");
            request.addHeader("Authorization", "Bearer " + token);
            request.addHeader("X-Client-Fingerprint", fingerprint);
            request.addHeader("X-Forwarded-For", "spoofed-" + i);
            var response = new MockHttpServletResponse();
            auth.doFilter(request, response, (req, res) -> reached.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(i < 10 ? 200 : 429);
            if (i == 10) {
                assertThat(response.getHeader("Retry-After")).isNotBlank();
                assertThat(response.getContentAsString()).contains("API_RATE_LIMITED");
            }
        }
        assertThat(reached).hasValue(10);
    }
}
