package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
import com.enterprise.shipping.shared.infrastructure.http.OutboundGuard;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.util.StringValueResolver;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Registers only the shared transport channel and account context for the sandbox provider.
 * Individual API operations self-register in their own vertical slice packages so adding
 * new provider APIs never modifies this class.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SandboxProviderProperties.class)
public class SandboxProviderConfig {

    @Bean("sandboxAccountContext")
    public ProviderAccountContext sandboxAccountContext(SandboxProviderProperties p) {
        return new ProviderAccountContext(p.providerId(), p.accountAlias(), p.labelHosts());
    }

    @Bean("sandboxHttpRouteResolver")
    public StringValueResolver sandboxHttpRouteResolver(SandboxProviderProperties properties) {
        var sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("sandbox-normalized-routes",
                Map.of("shipping.provider.shipments-path", properties.shipmentsPath())));
        var resolver = new PropertySourcesPropertyResolver(sources);
        return resolver::resolveRequiredPlaceholders;
    }

    @Bean("sandboxWebClient")
    public WebClient sandboxWebClient(
            SandboxProviderProperties p, OutboundClientRegistry outbound) {
        return outbound.registerClient(
                p.providerId(),
                p.baseUrl(),
                builder ->
                        builder.defaultHeaders(
                                headers ->
                                        headers.setBasicAuth(p.username(), p.password())));
    }

    @Bean("sandboxOutboundGuard")
    @DependsOn("sandboxWebClient")
    public OutboundGuard sandboxOutboundGuard(SandboxProviderProperties properties, OutboundClientRegistry registry) {
        return registry.guard(properties.providerId());
    }
}
