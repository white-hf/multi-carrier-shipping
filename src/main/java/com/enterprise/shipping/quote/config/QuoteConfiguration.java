package com.enterprise.shipping.quote.config;

import com.enterprise.shipping.quote.infrastructure.monitoring.MicrometerQuoteObserver;
import com.enterprise.shipping.quote.port.QuoteObserver;
import com.enterprise.shipping.shared.infrastructure.http.CarrierHttpClientFactory;
import com.enterprise.shipping.shared.infrastructure.http.CarrierHttpClientFactory.TransportRetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

/** Owns isolated rate-query connection pools; Spring disposes each pool on shutdown. */
@Configuration
public class QuoteConfiguration {
    @Bean QuoteObserver quoteObserver(MeterRegistry registry) { return new MicrometerQuoteObserver(registry); }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider fedexPool(QuoteProperties properties) {
        return CarrierHttpClientFactory.pool("fedex", properties.carrier("fedex").getConcurrency());
    }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider upsPool(QuoteProperties properties) {
        return CarrierHttpClientFactory.pool("ups", properties.carrier("ups").getConcurrency());
    }

    @Bean("fedexClient")
    WebClient fedexClient(@Qualifier("fedexPool") ConnectionProvider pool, QuoteProperties properties) {
        return client(pool, properties.carrier("fedex"));
    }

    @Bean("upsClient")
    WebClient upsClient(@Qualifier("upsPool") ConnectionProvider pool, QuoteProperties properties) {
        return client(pool, properties.carrier("ups"));
    }

    private WebClient client(ConnectionProvider pool, QuoteProperties.Carrier settings) {
        // Preserve the existing rate-query transport policy; booking explicitly disables retries.
        return CarrierHttpClientFactory.client(pool, settings.getBaseUrl(), settings.getTimeoutMs(), TransportRetry.ENABLED);
    }
}
