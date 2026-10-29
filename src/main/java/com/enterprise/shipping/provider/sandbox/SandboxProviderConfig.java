package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClient;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean("sandboxOutboundClient")
    public OutboundClient sandboxOutboundClient(
            SandboxProviderProperties p, OutboundClientRegistry outbound) {
        return outbound.registerClient(
                p.providerId(),
                p.baseUrl(),
                builder ->
                        builder.defaultHeaders(
                                headers ->
                                        headers.setBasicAuth(p.username(), p.password())));
    }
}
