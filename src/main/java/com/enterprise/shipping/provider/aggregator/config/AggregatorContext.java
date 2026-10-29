package com.enterprise.shipping.provider.aggregator.config;

import com.enterprise.shipping.shared.domain.ShippingException;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Shared provider account identity, URL allowlist, and standard read-status mapping
 * for independent API slices within the aggregator provider.
 */
public final class AggregatorContext {

    private final ProviderProperties properties;

    public AggregatorContext(ProviderProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public ProviderProperties properties() {
        return properties;
    }

    public boolean matchesContext(String providerId, String accountAlias) {
        return properties.providerId().equals(providerId)
                && properties.accountAlias().equals(accountAlias);
    }

    public void requireContext(String providerId, String accountAlias) {
        if (!matchesContext(providerId, accountAlias)) {
            throw ShippingException.integrationUnavailable("PROVIDER_CONTEXT_UNAVAILABLE");
        }
    }

    public boolean allowedUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme())
                    && uri.getUserInfo() == null
                    && uri.getHost() != null
                    && properties
                            .labelHosts()
                            .contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    public static ShippingException readFailure(int status) {
        return switch (status) {
            case 401 -> ShippingException.integrationUnavailable("PROVIDER_AUTHENTICATION_FAILED");
            case 429 -> ShippingException.dependencyFailure("PROVIDER_RATE_LIMITED");
            default ->
                    ShippingException.dependencyFailure(
                            status >= 500
                                    ? "PROVIDER_UNAVAILABLE"
                                    : "PROVIDER_UNEXPECTED_RESPONSE");
        };
    }
}
