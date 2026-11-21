package com.enterprise.shipping.support;

import com.enterprise.shipping.provider.sandbox.ProviderJson;
import com.enterprise.shipping.provider.sandbox.SandboxProviderProperties;
import com.enterprise.shipping.provider.sandbox.label.LabelHttpClient;
import com.enterprise.shipping.provider.sandbox.label.LabelHttpConfiguration;
import com.enterprise.shipping.provider.sandbox.label.LabelOperation;
import com.enterprise.shipping.provider.sandbox.rate.RateHttpClient;
import com.enterprise.shipping.provider.sandbox.rate.RateHttpConfiguration;
import com.enterprise.shipping.provider.sandbox.rate.RateOperation;
import com.enterprise.shipping.provider.sandbox.shipment.CreateOperation;
import com.enterprise.shipping.provider.sandbox.shipment.ShipmentHttpClient;
import com.enterprise.shipping.provider.sandbox.shipment.ShipmentHttpConfiguration;
import com.enterprise.shipping.shared.infrastructure.transport.OutboundProtection;
import com.enterprise.shipping.shared.infrastructure.transport.OutboundTransportFactory;
import com.enterprise.shipping.shipping.infrastructure.provider.SandboxLabelAdapter;
import com.enterprise.shipping.shipping.infrastructure.provider.SandboxRateAdapter;
import com.enterprise.shipping.shipping.infrastructure.provider.SandboxShipmentAdapter;
import com.enterprise.shipping.shipping.port.LabelProvider;
import com.enterprise.shipping.shipping.port.RateProvider;
import com.enterprise.shipping.shipping.port.ShipmentProvider;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

/** Exercises the production transport, proxies, protection and adapters against loopback servers. */
public final class ProviderFixture implements AutoCloseable {
    public final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    public final SandboxProviderProperties properties;
    public final Bulkhead capacity;
    public final CircuitBreaker circuit;
    public final OutboundProtection protection;
    public final ConnectionProvider pool;
    public final WebClient web;
    public final RateOperation rateOperation;
    public final CreateOperation createOperation;
    public final LabelOperation labelOperation;
    public final RateProvider rates;
    public final ShipmentProvider shipments;
    public final LabelProvider labels;

    public ProviderFixture(String baseUrl) {
        this(
                properties(baseUrl),
                "/rest/shipments",
                Duration.ofSeconds(90),
                Duration.ofSeconds(3),
                Duration.ofSeconds(3));
    }

    public ProviderFixture(
            SandboxProviderProperties properties,
            String shipmentsPath,
            Duration rateTimeout,
            Duration createTimeout,
            Duration readTimeout) {
        this.properties = properties;
        this.capacity = Bulkhead.of(
                "shipping-provider",
                BulkheadConfig.custom()
                        .maxConcurrentCalls(8)
                        .maxWaitDuration(Duration.ZERO)
                        .build());
        this.circuit = CircuitBreaker.of(
                "shipping-provider",
                CircuitBreakerConfig.custom()
                        .minimumNumberOfCalls(5)
                        .slidingWindowSize(10)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .ignoreException(error -> error instanceof com.enterprise.shipping.shared.domain.ShippingException shipping
                                && shipping.category().isCallerFault())
                        .build());
        this.protection = new OutboundProtection(
                properties.providerId(), capacity, circuit, metrics);
        this.pool = OutboundTransportFactory.pool(
                "provider-fixture", 8, Duration.ofMillis(25));
        this.web = OutboundTransportFactory.client(
                pool,
                properties.baseUrl(),
                properties.username(),
                properties.password(),
                properties.connectTimeout(),
                Duration.ofSeconds(60),
                properties.maxResponseBytes(),
                properties.providerId(),
                ProviderJson.mapper());
        RateHttpClient rateHttp = new RateHttpConfiguration().rateHttpClient(web);
        ShipmentHttpClient shipmentHttp =
                new ShipmentHttpConfiguration().shipmentHttpClient(web, shipmentsPath);
        LabelHttpClient labelHttp =
                new LabelHttpConfiguration().labelHttpClient(web, shipmentsPath);
        this.rateOperation = new RateOperation(
                rateHttp, protection, properties, rateTimeout, 10, 300, Clock.systemUTC());
        this.createOperation = new CreateOperation(
                shipmentHttp, protection, properties, createTimeout);
        this.labelOperation = new LabelOperation(
                labelHttp, protection, properties, readTimeout);
        try {
            metrics.gauge(
                    "shipping.outbound.inflight",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of(
                            "dependency", properties.providerId())),
                    capacity,
                    value -> value.getBulkheadConfig().getMaxConcurrentCalls()
                            - value.getMetrics().getAvailableConcurrentCalls());
            metrics.gauge(
                    "shipping.outbound.limit",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of(
                            "dependency", properties.providerId())),
                    capacity,
                    value -> value.getBulkheadConfig().getMaxConcurrentCalls());
            metrics.gauge(
                    "shipping.outbound.circuit.state",
                    java.util.List.of(
                            io.micrometer.core.instrument.Tag.of(
                                    "dependency", properties.providerId()),
                            io.micrometer.core.instrument.Tag.of("group", "default")),
                    circuit,
                    value -> value.getState().getOrder());
        } catch (RuntimeException ignored) {
            // Metrics must not change test admission behavior.
        }
        this.rates = new SandboxRateAdapter(rateOperation);
        this.shipments = new SandboxShipmentAdapter(createOperation);
        this.labels = new SandboxLabelAdapter(labelOperation);
    }

    public ProviderFixture(
            SandboxProviderProperties properties,
            Duration rateTimeout,
            Duration createTimeout,
            Duration readTimeout) {
        this(properties, "/rest/shipments", rateTimeout, createTimeout, readTimeout);
    }

    public static SandboxProviderProperties properties(String baseUrl) {
        return new SandboxProviderProperties(
                baseUrl,
                "stub",
                java.util.UUID.randomUUID().toString(),
                "sandbox",
                "account",
                Set.of("labels.example.com"),
                Duration.ofSeconds(3),
                4 * 1024 * 1024);
    }

    @Override
    public void close() {
        pool.disposeLater().block(Duration.ofSeconds(5));
        metrics.close();
    }
}
