package com.enterprise.shipping.shared.infrastructure.http;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboundProperties.class)
public class OutboundConfiguration {
    @Bean(destroyMethod = "close")
    OutboundClientRegistry outboundClientRegistry(
            OutboundProperties properties, MeterRegistry metrics) {
        return new OutboundClientRegistry(properties, metrics);
    }
}
