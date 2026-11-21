package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.transport.OutboundProtection;
import com.enterprise.shipping.shared.infrastructure.transport.OutboundTransportFactory;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Registers only this provider's transport and protection. API slices create their own HTTP proxy
 * beans, so adding an API never modifies this class.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SandboxProviderProperties.class)
public class SandboxProviderConfig {
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider sandboxConnectionPool(BulkheadRegistry bulkheads) {
        int limit = bulkheads.bulkhead("shipping-provider")
                .getBulkheadConfig()
                .getMaxConcurrentCalls();
        return OutboundTransportFactory.pool(
                "shipping-provider",
                limit,
                Duration.ofMillis(25));
    }

    @Bean
    public WebClient sandboxWebClient(
            SandboxProviderProperties properties,
            ConnectionProvider sandboxConnectionPool,
            @Value("${shipping.provider.transport-response-timeout:120s}") Duration responseTimeout) {
        return OutboundTransportFactory.client(
                sandboxConnectionPool,
                properties.baseUrl(),
                properties.username(),
                properties.password(),
                properties.connectTimeout(),
                responseTimeout,
                properties.maxResponseBytes(),
                properties.providerId(),
                ProviderJson.mapper());
    }

    @Bean
    public CircuitBreakerConfigCustomizer sandboxCircuitPolicy() {
        return CircuitBreakerConfigCustomizer.of(
                "shipping-provider",
                builder -> builder.ignoreException(error -> {
                    if (error instanceof ShippingException shipping) {
                        return shipping.category().isCallerFault();
                    }
                    return false;
                }));
    }

    @Bean
    public OutboundProtection sandboxProtection(
            SandboxProviderProperties properties,
            BulkheadRegistry bulkheads,
            CircuitBreakerRegistry circuits,
            MeterRegistry metrics) {
        var capacity = bulkheads.bulkhead("shipping-provider");
        var circuit = circuits.circuitBreaker("shipping-provider");
        try {
            metrics.gauge(
                    "shipping.outbound.inflight",
                    List.of(Tag.of("dependency", properties.providerId())),
                    capacity,
                    value -> value.getBulkheadConfig().getMaxConcurrentCalls()
                            - value.getMetrics().getAvailableConcurrentCalls());
            metrics.gauge(
                    "shipping.outbound.limit",
                    List.of(Tag.of("dependency", properties.providerId())),
                    capacity,
                    value -> value.getBulkheadConfig().getMaxConcurrentCalls());
            metrics.gauge(
                    "shipping.outbound.circuit.state",
                    List.of(
                            Tag.of("dependency", properties.providerId()),
                            Tag.of("group", "default")),
                    circuit,
                    value -> value.getState().getOrder());
        } catch (RuntimeException ignored) {
            // Metrics must not prevent startup or alter provider admission.
        }
        return new OutboundProtection(properties.providerId(), capacity, circuit, metrics);
    }
}
