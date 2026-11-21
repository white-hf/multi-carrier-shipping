package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.shared.domain.ShippingException;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Cross-cutting provider identity and transport settings; per-API budgets live with each API. */
@Validated
@ConfigurationProperties("shipping.provider")
public record SandboxProviderProperties(
        @NotBlank String baseUrl,
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String providerId,
        @NotBlank String accountAlias,
        Set<String> labelHosts,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("4194304") int maxResponseBytes) {

    @Override
    public String toString() {
        return "SandboxProviderProperties[providerId=" + providerId + ", credentials=REDACTED]";
    }

    public SandboxProviderProperties {
        if (baseUrl != null && !baseUrl.isBlank()) {
            URI uri = URI.create(baseUrl);
            if (uri.getUserInfo() != null
                    || uri.getHost() == null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !("https".equals(uri.getScheme())
                            || "http".equals(uri.getScheme())
                                    && Set.of("127.0.0.1", "localhost", "::1")
                                            .contains(uri.getHost()))) {
                throw new IllegalArgumentException(
                        "Provider URL must use HTTPS, except loopback HTTP test servers");
            }
        }
        labelHosts =
                labelHosts == null
                        ? Set.of()
                        : labelHosts.stream()
                                .map(String::strip)
                                .filter(value -> !value.isEmpty())
                                .map(value -> value.toLowerCase(Locale.ROOT))
                                .collect(Collectors.toUnmodifiableSet());
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        if (connectTimeout.isZero()
                || connectTimeout.isNegative()
                || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || maxResponseBytes < 64
                || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid provider transport bounds");
        }
    }

    public void requireContext(String provider, String account) {
        if (!providerId.equals(provider) || !accountAlias.equals(account)) {
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
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
