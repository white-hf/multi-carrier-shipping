package com.enterprise.shipping.provider.sandbox;

import java.util.Map;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.util.StringValueResolver;

/** Resolves provider-owned route placeholders without globally mutating application properties. */
public final class ProviderRouteResolver {
    public static final String DEFAULT_SHIPMENTS_PATH = "/rest/shipments";

    private ProviderRouteResolver() {
    }

    public static StringValueResolver shipments(String rawPath) {
        String path = rawPath == null || rawPath.isBlank() ? DEFAULT_SHIPMENTS_PATH : rawPath;
        if (!path.startsWith("/")
                || path.contains("..")
                || path.contains("?")
                || path.contains("\\")) {
            throw new IllegalArgumentException("Invalid shipment resource path");
        }
        var sources = new MutablePropertySources();
        sources.addFirst(
                new MapPropertySource(
                        "sandbox-normalized-routes",
                        Map.of("shipping.provider.shipments-path", path)));
        var resolver = new PropertySourcesPropertyResolver(sources);
        return resolver::resolveRequiredPlaceholders;
    }
}
