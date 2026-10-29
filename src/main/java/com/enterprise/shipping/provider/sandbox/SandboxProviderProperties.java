package com.enterprise.shipping.provider.sandbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("shipping.provider")
public record SandboxProviderProperties(
        @NotBlank String baseUrl,
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String providerId,
        @NotBlank String accountAlias,
        String shipmentsPath,
        @NotNull Duration rateTimeout,
        @NotNull Duration createTimeout,
        @NotNull Duration readTimeout,
        @Min(5) @Max(30) int collectionSeconds,
        @Min(10) @Max(840) int quoteLifetimeSeconds,
        Set<String> labelHosts) {

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
        shipmentsPath =
                shipmentsPath == null || shipmentsPath.isBlank()
                        ? "/rest/shipments"
                        : shipmentsPath;
        if (!shipmentsPath.startsWith("/")
                || shipmentsPath.contains("..")
                || shipmentsPath.contains("?")) {
            throw new IllegalArgumentException("Invalid shipment resource path");
        }
        labelHosts =
                labelHosts == null
                        ? Set.of()
                        : labelHosts.stream()
                                .map(String::strip)
                                .filter(v -> !v.isEmpty())
                                .map(v -> v.toLowerCase(Locale.ROOT))
                                .collect(Collectors.toUnmodifiableSet());
        for (Duration duration : new Duration[] {rateTimeout, createTimeout, readTimeout}) {
            if (duration != null && (duration.isNegative() || duration.isZero())) {
                throw new IllegalArgumentException("Positive provider deadlines required");
            }
        }
        if (rateTimeout != null && rateTimeout.toSeconds() <= collectionSeconds) {
            throw new IllegalArgumentException("Rate deadline must exceed collection window");
        }
    }
}
