package com.enterprise.shipping.shared.infrastructure.http;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/** Construction reuse only: callers own separate pools and their Spring shutdown lifecycle. */
public final class CarrierHttpClientFactory {
    private static final Duration CONNECTION_ACQUIRE_TIMEOUT = Duration.ofMillis(25);
    private static final Duration CLIENT_WARMUP_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    public enum TransportRetry { ENABLED, DISABLED }

    private CarrierHttpClientFactory() {}

    public static ConnectionProvider pool(String name, int concurrency) {
        return ConnectionProvider.builder(name).maxConnections(concurrency)
                .pendingAcquireMaxCount(concurrency).pendingAcquireTimeout(CONNECTION_ACQUIRE_TIMEOUT)
                .metrics(true).build();
    }

    public static WebClient client(ConnectionProvider pool, String baseUrl, long timeoutMs, TransportRetry retry) {
        HttpClient http = HttpClient.create(pool)
                .disableRetry(retry == TransportRetry.DISABLED)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(timeoutMs))
                .responseTimeout(Duration.ofMillis(timeoutMs));
        // Initialize event loops without making a carrier request inside a short first-call budget.
        http.warmup().block(CLIENT_WARMUP_TIMEOUT);
        return WebClient.builder().baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES)).build();
    }
}
