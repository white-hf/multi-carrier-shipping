package com.enterprise.shipping.auth.infrastructure;

import com.enterprise.shipping.auth.application.GuestSessionTokenService;
import com.enterprise.shipping.shared.api.ShippingRequestTracingFilter;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingAuthenticationFilterTest {
    private final GuestSessionTokenService tokens = new GuestSessionTokenService("test-secret", Duration.ofHours(2), Clock.systemUTC());
    private final ShippingAuthenticationFilter auth = new ShippingAuthenticationFilter(tokens, true);
    private final ShippingRequestTracingFilter tracing = new ShippingRequestTracingFilter();

    @Test
    void authenticationRejectionRetainsCorrelationAndClearsMdc() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/quotes");
        var response = new MockHttpServletResponse();
        tracing.doFilter(request, response, (req, res) -> auth.doFilter(req, res,
                (ignoredRequest, ignoredResponse) -> { throw new AssertionError("Unauthenticated call reached application"); }));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        assertThat(response.getContentAsString()).contains(response.getHeader("X-Request-Id"));
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void guestIssuanceRemainsProtectedAgainstCrossOriginWrites() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/auth/guest");
        request.addHeader("Origin", "https://untrusted.example");
        var response = new MockHttpServletResponse();
        tracing.doFilter(request, response, (req, res) -> auth.doFilter(req, res,
                (ignoredRequest, ignoredResponse) -> { throw new AssertionError("Cross-origin request was accepted"); }));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ORIGIN_NOT_ALLOWED", response.getHeader("X-Request-Id"));
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
        tracing.doFilter(request, response, (req, res) -> auth.doFilter(req, res, (verifiedRequest, verifiedResponse) -> {
            assertThat(verifiedRequest.getAttribute(ShippingAuthenticationFilter.AUTHENTICATED_USER_ATTR)).isEqualTo("test-user");
            assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-Id"));
        }));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(MDC.get("requestId")).isNull();
    }
}
