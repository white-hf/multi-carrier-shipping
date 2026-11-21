package com.enterprise.shipping.provider.sandbox.label;

import com.enterprise.shipping.provider.sandbox.ProviderFailures;
import com.enterprise.shipping.provider.sandbox.SandboxProviderProperties;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.transport.OutboundDiagnostics;
import com.enterprise.shipping.shared.infrastructure.transport.OutboundProtection;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/** Label API: identifier validation, pending/empty semantics and allowlisted URL decoding. */
@Component
public class LabelOperation {
    private final LabelHttpClient http;
    private final OutboundProtection protection;
    private final SandboxProviderProperties properties;
    private final Duration readTimeout;
    private final LabelResponseDecoder decoder;

    @Autowired
    public LabelOperation(
            LabelHttpClient http,
            OutboundProtection protection,
            SandboxProviderProperties properties,
            @Value("${shipping.provider.read-timeout:30s}") Duration readTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.properties = Objects.requireNonNull(properties, "properties");
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("Positive label deadline required");
        }
        this.readTimeout = readTimeout;
        this.decoder = new LabelResponseDecoder(properties::allowedUrl);
    }

    public LabelData fetch(LabelIdentity identity) {
        // 1. Verify provider/account context and validate shipment identifier syntax.
        properties.requireContext(identity.providerId(), identity.accountAlias());
        String id = identity.shipmentId();
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw ProviderFailures.invalidResponse();
        }

        // 2. Spring binds the path variable; decode PDF/URL data inside the protected scope.
        var diagnostic = OutboundDiagnostics.call(
                properties.providerId(),
                "labels",
                status -> status == 202 || status == 404
                        ? OutboundDiagnostics.Classification.EXPECTED_BRANCH
                        : status >= 200 && status < 300
                                ? OutboundDiagnostics.Classification.SUCCESS
                                : OutboundDiagnostics.Classification.DEPENDENCY_FAILURE);
        var response = Mono.defer(() -> http.label(id))
                .flatMap(entity -> {
                    try {
                        return Mono.justOrEmpty(decoder.decode(entity));
                    } catch (ShippingException invalid) {
                        diagnostic.protocolError();
                        return Mono.error(invalid);
                    }
                })
                .onErrorResume(WebClientResponseException.class, failure -> {
                    int status = failure.getStatusCode().value();
                    return status == 202 || status == 404
                            ? Mono.empty() : Mono.error(ProviderFailures.readFailure(status));
                })
                .contextWrite(diagnostic::context);

        try {
            return protection.await("labels", after(readTimeout), response);
        } catch (BulkheadFullException | CallNotPermittedException unsent) {
            throw new ShippingException(
                    "PROVIDER_CAPACITY_EXCEEDED",
                    ShippingException.Category.LOCAL_CAPACITY);
        }
    }

    public boolean allowedUrl(String url) {
        return properties.allowedUrl(url);
    }

    private static long after(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }
}
