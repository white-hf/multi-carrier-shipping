package com.enterprise.shipping.shared.config;

import com.enterprise.shipping.shared.api.WorkflowRequestFilter;
import com.enterprise.shipping.shared.identity.GuestSessionTokenService;
import com.enterprise.shipping.shared.identity.UserContext;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Configures shared time and unified user/guest identity resolution under the 'workflow' profile.
 */
@Configuration(proxyBeanMethods = false)
@Profile("workflow")
public class WorkflowContextConfiguration {

    @Bean
    Clock workflowClock() {
        return Clock.systemUTC();
    }

    @Bean
    GuestSessionTokenService guestSessionTokenService(
            @Value("${shipping.auth.token-secret:local-workflow-hmac-secret-key-2026}") String secret,
            @Value("${shipping.auth.token-ttl:2h}") Duration tokenTtl,
            Clock clock) {
        return new GuestSessionTokenService(secret, tokenTtl, clock);
    }

    @Bean
    UserContext workflowUser(@Value("${shipping.demo-user-id:demo-user}") String fallbackId) {
        if (fallbackId.isBlank() || fallbackId.length() > 100) {
            throw new IllegalArgumentException("A valid fallback demo user ID is required");
        }

        // Resolve the verified principal from the active request context so Guest and future
        // logged-in users are handled identically by downstream controllers and services.
        return () -> {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                Object verified = servletAttributes.getRequest()
                        .getAttribute(WorkflowRequestFilter.AUTHENTICATED_USER_ATTR);
                if (verified instanceof String userId && !userId.isBlank()) {
                    return userId;
                }
            }
            return fallbackId;
        };
    }
}
