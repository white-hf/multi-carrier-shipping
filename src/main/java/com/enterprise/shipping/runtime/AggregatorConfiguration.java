package com.enterprise.shipping.runtime;

import com.enterprise.shipping.booking.infrastructure.provider.AggregatorLabelAdapter;
import com.enterprise.shipping.booking.infrastructure.provider.AggregatorShipmentAdapter;
import com.enterprise.shipping.provider.aggregator.AggregatorProviderClient;
import com.enterprise.shipping.provider.aggregator.config.ProviderProperties;
import com.enterprise.shipping.quote.infrastructure.provider.AggregatorRateAdapter;
import com.enterprise.shipping.reconciliation.infrastructure.provider.AggregatorRecoveryAdapter;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Deployment composition wires consumer-owned ports without provider-to-business dependencies. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
public class AggregatorConfiguration {

    @Bean
    AggregatorProviderClient aggregatorProviderClient(
            ProviderProperties p,
            Clock clock,
            OutboundClientRegistry outbound) {
        return new AggregatorProviderClient(p, clock, outbound);
    }

    @Bean
    AggregatorRateAdapter aggregatorRateAdapter(AggregatorProviderClient client) {
        return new AggregatorRateAdapter(client);
    }

    @Bean
    AggregatorShipmentAdapter aggregatorShipmentAdapter(AggregatorProviderClient client) {
        return new AggregatorShipmentAdapter(client);
    }

    @Bean
    AggregatorLabelAdapter aggregatorLabelAdapter(AggregatorProviderClient client) {
        return new AggregatorLabelAdapter(client);
    }

    @Bean
    AggregatorRecoveryAdapter aggregatorRecoveryAdapter(AggregatorProviderClient client) {
        return new AggregatorRecoveryAdapter(client);
    }
}
