package com.enterprise.shipping.runtime;

import com.enterprise.shipping.booking.infrastructure.provider.SandboxLabelAdapter;
import com.enterprise.shipping.booking.infrastructure.provider.SandboxShipmentAdapter;
import com.enterprise.shipping.provider.sandbox.SandboxProviderClient;
import com.enterprise.shipping.provider.sandbox.config.ProviderProperties;
import com.enterprise.shipping.quote.infrastructure.provider.SandboxRateAdapter;
import com.enterprise.shipping.reconciliation.infrastructure.provider.SandboxRecoveryAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Deployment composition wires consumer-owned ports without provider-to-business dependencies. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
public class SandboxConfiguration {

    @Bean
    SandboxProviderClient sandboxProviderClient(
            ProviderProperties p,
            ObjectMapper mapper,
            Clock clock,
            OutboundClientRegistry outbound) {
        return new SandboxProviderClient(p, mapper, clock, outbound);
    }

    @Bean
    SandboxRateAdapter sandboxRateAdapter(SandboxProviderClient client) {
        return new SandboxRateAdapter(client);
    }

    @Bean
    SandboxShipmentAdapter sandboxShipmentAdapter(SandboxProviderClient client) {
        return new SandboxShipmentAdapter(client);
    }

    @Bean
    SandboxLabelAdapter sandboxLabelAdapter(SandboxProviderClient client) {
        return new SandboxLabelAdapter(client);
    }

    @Bean
    SandboxRecoveryAdapter sandboxRecoveryAdapter(SandboxProviderClient client) {
        return new SandboxRecoveryAdapter(client);
    }
}
