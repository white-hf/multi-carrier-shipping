package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
import com.enterprise.shipping.provider.sandbox.labels.SandboxLabelOperation;
import com.enterprise.shipping.provider.sandbox.rates.SandboxRateOperation;
import com.enterprise.shipping.provider.sandbox.shipments.SandboxCreateOperation;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClient;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Self-contained configuration for the sandbox provider: registers its transport channel,
 * shared ProviderAccountContext, and independent API operations (rates, shipments, labels).
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

    @Bean
    public SandboxRateOperation sandboxRateOperation(
            @Qualifier("sandboxOutboundClient") OutboundClient outbound,
            @Qualifier("sandboxAccountContext") ProviderAccountContext context,
            SandboxProviderProperties p,
            Clock clock) {
        return new SandboxRateOperation(
                outbound,
                context,
                p.collectionSeconds(),
                p.quoteLifetimeSeconds(),
                clock);
    }

    @Bean
    public SandboxCreateOperation sandboxCreateOperation(
            @Qualifier("sandboxOutboundClient") OutboundClient outbound,
            @Qualifier("sandboxAccountContext") ProviderAccountContext context,
            SandboxProviderProperties p) {
        return new SandboxCreateOperation(
                outbound, context, p.shipmentsPath(), p.createTimeout());
    }

    @Bean
    public SandboxLabelOperation sandboxLabelOperation(
            @Qualifier("sandboxOutboundClient") OutboundClient outbound,
            @Qualifier("sandboxAccountContext") ProviderAccountContext context,
            SandboxProviderProperties p) {
        return new SandboxLabelOperation(outbound, context, p.shipmentsPath(), p.readTimeout());
    }
}
