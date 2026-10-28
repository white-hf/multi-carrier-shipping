package com.enterprise.shipping.quote.config;

import com.enterprise.shipping.quote.application.HighFanOutAggregationEngine;
import com.enterprise.shipping.quote.application.QuoteApplicationService;
import com.enterprise.shipping.quote.application.SingleProviderRateEngine;
import com.enterprise.shipping.quote.infrastructure.monitoring.MicrometerQuoteObserver;
import com.enterprise.shipping.quote.infrastructure.monitoring.MicrometerShippingQuoteObserver;
import com.enterprise.shipping.quote.infrastructure.persistence.JdbcQuoteStore;
import com.enterprise.shipping.quote.port.CarrierRatePort;
import com.enterprise.shipping.quote.port.QuoteObserver;
import com.enterprise.shipping.quote.port.QuoteStore;
import com.enterprise.shipping.quote.port.RateComparisonPort;
import com.enterprise.shipping.quote.port.RateProviderPort;
import com.enterprise.shipping.quote.port.ShippingQuoteObserver;
import com.enterprise.shipping.shared.domain.ShippingMarketPolicy;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import com.enterprise.shipping.quote.infrastructure.provider.ResilientCarrierRateAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the quote module, supporting both single-provider aggregator rating and
 * direct multi-carrier high-fan-out aggregation behind RateComparisonPort.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QuoteProperties.class)
public class ShippingQuoteConfiguration {

    @Bean
    QuoteObserver quoteObserver(MeterRegistry metrics) {
        return new MicrometerQuoteObserver(metrics);
    }

    @Bean
    ShippingQuoteObserver shippingQuoteObserver(MeterRegistry metrics) {
        return new MicrometerShippingQuoteObserver(metrics);
    }

    @Bean
    QuoteStore quoteStore(DataSource source) {
        return new JdbcQuoteStore(
                new JdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    @Bean
    RateComparisonPort rateComparisonPort(
            RateProviderPort provider,
            ObjectProvider<List<CarrierRatePort>> directCarriers,
            OutboundClientRegistry outbound,
            QuoteObserver fanOutObserver,
            QuoteProperties properties,
            ShippingMarketPolicy market) {
        List<CarrierRatePort> carriers = directCarriers.getIfAvailable(List::of);
        if (carriers != null && !carriers.isEmpty()) {
            var protectedCarriers =
                    carriers.stream()
                            .map(
                                    carrier ->
                                            (CarrierRatePort)
                                                    new ResilientCarrierRateAdapter(
                                                            carrier,
                                                            outbound.registerGuard(
                                                                    carrier.getCarrierId(),
                                                                    java.util.Set.of("rates")),
                                                            market))
                            .toList();
            return new HighFanOutAggregationEngine(
                    protectedCarriers, fanOutObserver, properties, market);
        }
        return new SingleProviderRateEngine(provider);
    }

    @Bean
    QuoteApplicationService quoteApplicationService(
            QuoteStore store,
            RateComparisonPort engine,
            Clock clock,
            @Value("${shipping.provider.rate-timeout}") Duration timeout,
            @Value("${shipping.business-timezone:America/Toronto}") String zone,
            ShippingQuoteObserver observer) {
        return new QuoteApplicationService(
                store, engine, clock, timeout, ZoneId.of(zone), observer);
    }
}
