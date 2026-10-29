package com.enterprise.shipping.runtime;

import com.enterprise.shipping.booking.infrastructure.provider.AggregatorLabelAdapter;
import com.enterprise.shipping.booking.infrastructure.provider.AggregatorShipmentAdapter;
import com.enterprise.shipping.provider.aggregator.config.AggregatorContext;
import com.enterprise.shipping.provider.aggregator.config.ProviderProperties;
import com.enterprise.shipping.provider.aggregator.labels.AggregatorLabelApi;
import com.enterprise.shipping.provider.aggregator.rates.AggregatorRateApi;
import com.enterprise.shipping.provider.aggregator.shipments.AggregatorShipmentApi;
import com.enterprise.shipping.quote.infrastructure.provider.AggregatorRateAdapter;
import com.enterprise.shipping.reconciliation.infrastructure.provider.AggregatorRecoveryAdapter;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClient;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deployment composition wires independent provider API slices to consumer-owned ports
 * without centralizing all provider operations in a single God client.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
public class AggregatorConfiguration {

    @Bean
    AggregatorContext aggregatorContext(ProviderProperties p) {
        return new AggregatorContext(p);
    }

    @Bean("aggregatorOutboundClient")
    OutboundClient aggregatorOutboundClient(
            ProviderProperties p, OutboundClientRegistry outbound) {
        return outbound.registerClient(
                p.providerId(),
                p.baseUrl(),
                builder ->
                        builder.defaultHeaders(
                                headers ->
                                        headers.setBasicAuth(p.username(), p.password())));
    }

    @Bean
    AggregatorRateApi aggregatorRateApi(
            @Qualifier("aggregatorOutboundClient") OutboundClient outbound,
            AggregatorContext context,
            Clock clock) {
        return new AggregatorRateApi(outbound, context, clock);
    }

    @Bean
    AggregatorShipmentApi aggregatorShipmentApi(
            @Qualifier("aggregatorOutboundClient") OutboundClient outbound,
            AggregatorContext context) {
        return new AggregatorShipmentApi(outbound, context);
    }

    @Bean
    AggregatorLabelApi aggregatorLabelApi(
            @Qualifier("aggregatorOutboundClient") OutboundClient outbound,
            AggregatorContext context) {
        return new AggregatorLabelApi(outbound, context);
    }

    @Bean
    AggregatorRateAdapter aggregatorRateAdapter(AggregatorRateApi rateApi) {
        return new AggregatorRateAdapter(rateApi);
    }

    @Bean
    AggregatorShipmentAdapter aggregatorShipmentAdapter(AggregatorShipmentApi shipmentApi) {
        return new AggregatorShipmentAdapter(shipmentApi);
    }

    @Bean
    AggregatorLabelAdapter aggregatorLabelAdapter(AggregatorLabelApi labelApi) {
        return new AggregatorLabelAdapter(labelApi);
    }

    @Bean
    AggregatorRecoveryAdapter aggregatorRecoveryAdapter(AggregatorShipmentApi shipmentApi) {
        return new AggregatorRecoveryAdapter(shipmentApi);
    }
}
