package com.enterprise.shipping.quote.config;

import com.enterprise.shipping.quote.infrastructure.monitoring.MicrometerQuoteObserver;
import com.enterprise.shipping.quote.port.QuoteObserver;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shipping.quote.enabled", havingValue = "true")
public class QuoteConfiguration {
    @Bean QuoteObserver quoteObserver(MeterRegistry registry) { return new MicrometerQuoteObserver(registry); }
}
