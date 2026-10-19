package com.enterprise.shipping.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/** Owns dedicated courier connection pools; Spring disposes them during shutdown. */
@Configuration
public class CarrierClientConfiguration {
    private static final Duration CONNECTION_ACQUIRE_TIMEOUT = Duration.ofMillis(25);
    private static final Duration CLIENT_WARMUP_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    @Bean(destroyMethod = "dispose")
    ConnectionProvider fedexPool(ShippingEngineConstants properties) {
        return pool("fedex", properties);
    }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider upsPool(ShippingEngineConstants properties) {
        return pool("ups", properties);
    }

    @Bean("fedexClient")
    WebClient fedexClient(
            @Qualifier("fedexPool") ConnectionProvider pool, ShippingEngineConstants properties) {
        return client(pool, properties.carrier("fedex"));
    }

    @Bean("upsClient")
    WebClient upsClient(
            @Qualifier("upsPool") ConnectionProvider pool, ShippingEngineConstants properties) {
        return client(pool, properties.carrier("ups"));
    }

    private ConnectionProvider pool(String carrierId, ShippingEngineConstants properties) {
        var settings = properties.carrier(carrierId);
        // Separate providers isolate capacity even when demo couriers share one host and port.
        // Bound both the number of acquisition waiters and how long they can wait.
        return ConnectionProvider.builder(carrierId)
                .maxConnections(settings.getConcurrency())
                .pendingAcquireMaxCount(settings.getConcurrency())
                .pendingAcquireTimeout(CONNECTION_ACQUIRE_TIMEOUT)
                .metrics(true)
                .build();
    }

    private WebClient client(ConnectionProvider pool, ShippingEngineConstants.Carrier settings) {
        HttpClient http = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(settings.getTimeoutMs()))
                .responseTimeout(Duration.ofMillis(settings.getTimeoutMs()));
        // Avoid spending a short first-request budget initializing event loops and native resources.
        // This initializes client infrastructure; it does not contact or preconnect to a courier.
        http.warmup().block(CLIENT_WARMUP_TIMEOUT);
        return WebClient.builder()
                .baseUrl(settings.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
    }
}
