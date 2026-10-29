package com.enterprise.shipping.provider.core;

import com.enterprise.shipping.shared.domain.ShippingException;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Vendor-neutral provider account identity, URL allowlist, and standard read-status mapping
 * shared across all provider implementations (e.g. sandbox, ups, fedex).
 */
public final class ProviderAccountContext {

    private final String providerId;
    private final String accountAlias;
    private final Set<String> labelHosts;

    public ProviderAccountContext(
            String providerId,
            String accountAlias,
            Set<String> labelHosts) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.accountAlias = Objects.requireNonNull(accountAlias, "accountAlias");
        this.labelHosts =
                labelHosts == null
                        ? Set.of()
                        : labelHosts.stream()
                                .filter(Objects::nonNull)
                                .map(String::strip)
                                .filter(v -> !v.isEmpty())
                                .map(v -> v.toLowerCase(Locale.ROOT))
                                .collect(Collectors.toUnmodifiableSet());
    }

    public String providerId() {
        return providerId;
    }

    public String accountAlias() {
        return accountAlias;
    }

    public boolean matchesContext(String candidateProviderId, String candidateAccountAlias) {
        return providerId.equals(candidateProviderId)
                && accountAlias.equals(candidateAccountAlias);
    }

    public void requireContext(String candidateProviderId, String candidateAccountAlias) {
        if (!matchesContext(candidateProviderId, candidateAccountAlias)) {
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
                    && labelHosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
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
