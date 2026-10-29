package com.enterprise.shipping.auth.config;

import com.enterprise.shipping.auth.application.SessionTokenService;
import com.enterprise.shipping.auth.infrastructure.ShippingAuthenticationFilter;
import com.enterprise.shipping.shared.identity.UserContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;

/**
 * Owns token configuration and the authenticated identity exposed through the shared UserContext
 * contract.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShippingAuthProperties.class)
public class ShippingAuthConfiguration {

    @Bean
    SessionTokenService sessionTokenService(ShippingAuthProperties properties, Clock clock) {
        return new SessionTokenService(properties.tokenSecret(), properties.tokenTtl(), clock);
    }

    @Bean
    UserContext shippingUser(@Value("${shipping.default-user-id:guest-user}") String fallbackId) {
        if (fallbackId.isBlank() || fallbackId.length() > 100) {
            throw new IllegalArgumentException("A valid fallback user ID is required");
        }

        // Resolve the verified principal from the active request context so authenticated
        // account users are handled identically by downstream controllers and services.
        return () -> {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                Object verified =
                        servletAttributes
                                .getRequest()
                                .getAttribute(ShippingAuthenticationFilter.AUTHENTICATED_USER_ATTR);
                if (verified instanceof String userId && !userId.isBlank()) {
                    return userId;
                }
            }
            return fallbackId;
        };
    }
}
