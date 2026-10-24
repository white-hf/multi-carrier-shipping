package com.enterprise.shipping.auth.config;

import com.enterprise.shipping.auth.application.GuestSessionTokenService;
import com.enterprise.shipping.auth.infrastructure.WorkflowAuthenticationFilter;
import com.enterprise.shipping.shared.identity.UserContext;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.web.context.request.*;

/** Owns token configuration and the authenticated identity exposed through the shared UserContext contract. */
@Configuration(proxyBeanMethods = false)
@Profile("workflow")
public class WorkflowAuthConfiguration {
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
                        .getAttribute(WorkflowAuthenticationFilter.AUTHENTICATED_USER_ATTR);
                if (verified instanceof String userId && !userId.isBlank()) {
                    return userId;
                }
            }
            return fallbackId;
        };
    }
}
