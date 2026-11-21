package com.enterprise.shipping.provider.sandbox.rate;

import com.enterprise.shipping.provider.sandbox.ProviderFailures;
import com.enterprise.shipping.provider.sandbox.ProviderJson;
import com.enterprise.shipping.provider.sandbox.SandboxProviderProperties;
import com.enterprise.shipping.provider.sandbox.rate.dto.AddressPayload;
import com.enterprise.shipping.provider.sandbox.rate.dto.LineItemPayload;
import com.enterprise.shipping.provider.sandbox.rate.dto.RateApiRequest;
import com.enterprise.shipping.provider.sandbox.rate.dto.RateApiResponse;
import com.enterprise.shipping.provider.sandbox.rate.dto.RateItem;
import com.enterprise.shipping.shared.domain.DimensionUnit;
import com.enterprise.shipping.shared.domain.ShippingAddress;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.domain.WeightUnit;
import com.enterprise.shipping.shared.infrastructure.transport.OutboundDiagnostics;
import com.enterprise.shipping.shared.infrastructure.transport.OutboundProtection;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.codec.DecodingException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/** Rate API: request conversion, status meaning and offer normalization for this provider. */
@Component
public class RateOperation {
    private static final BigDecimal POUNDS_TO_KILOGRAMS = new BigDecimal("0.45359237");
    private final RateHttpClient http;
    private final OutboundProtection protection;
    private final SandboxProviderProperties properties;
    private final Duration rateTimeout;
    private final int collectionSeconds;
    private final int quoteLifetimeSeconds;
    private final Clock clock;

    @Autowired
    public RateOperation(
            RateHttpClient http,
            OutboundProtection protection,
            SandboxProviderProperties properties,
            @Value("${shipping.provider.rate-timeout:90s}") Duration rateTimeout,
            @Value("${shipping.provider.collection-seconds:30}") int collectionSeconds,
            @Value("${shipping.provider.quote-lifetime-seconds:780}") int quoteLifetimeSeconds,
            Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (rateTimeout == null || rateTimeout.isZero() || rateTimeout.isNegative()) {
            throw new IllegalArgumentException("Positive rate deadline required");
        }
        if (collectionSeconds < 5 || collectionSeconds > 30) {
            throw new IllegalArgumentException("Collection window out of bounds");
        }
        if (quoteLifetimeSeconds < 10 || quoteLifetimeSeconds > 840) {
            throw new IllegalArgumentException("Quote lifetime out of bounds");
        }
        if (rateTimeout.toSeconds() <= collectionSeconds) {
            throw new IllegalArgumentException("Rate deadline must exceed collection window");
        }
        this.rateTimeout = rateTimeout;
        this.collectionSeconds = collectionSeconds;
        this.quoteLifetimeSeconds = quoteLifetimeSeconds;
    }

    public List<RateOffer> fetchRates(ShippingIntent intent, long callerDeadlineNanos) {
        // 1. Preserve request conversion and the caller's remaining absolute budget.
        var request = toRateRequest(intent);
        var diagnostic = OutboundDiagnostics.call(
                properties.providerId(),
                "rates",
                status -> switch (status) {
                    case 200 -> OutboundDiagnostics.Classification.SUCCESS;
                    case 404 -> OutboundDiagnostics.Classification.EXPECTED_BRANCH;
                    default -> OutboundDiagnostics.Classification.DEPENDENCY_FAILURE;
                });

        // 2. Decode, validate and interpret provider status inside admission and deadline scope.
        var response = Mono.defer(() -> http.rates(request))
                .map(entity -> {
                    try {
                        return offers(entity.getBody());
                    } catch (ShippingException invalid) {
                        diagnostic.protocolError();
                        throw invalid;
                    }
                })
                .onErrorMap(DecodingException.class, invalid -> {
                    diagnostic.protocolError();
                    return ProviderJson.invalidResponse(invalid);
                })
                .onErrorResume(WebClientResponseException.class, failure -> {
                    int status = failure.getStatusCode().value();
                    if (status == 404) {
                        return Mono.just(List.of());
                    }
                    return Mono.error(
                            status == 400
                                    ? ShippingException.invalidInput("PROVIDER_REQUEST_REJECTED")
                                    : ProviderFailures.readFailure(status));
                })
                .contextWrite(diagnostic::context);

        long deadline = Math.min(callerDeadlineNanos, after(rateTimeout));
        try {
            return protection.await("rates", deadline, response);
        } catch (BulkheadFullException | CallNotPermittedException unsent) {
            throw new ShippingException(
                    "PROVIDER_CAPACITY_EXCEEDED",
                    ShippingException.Category.LOCAL_CAPACITY);
        }
    }

    public RateApiRequest toRateRequest(ShippingIntent intent) {
        var parcel = intent.parcel();
        boolean imperial = parcel.dimensionUnit() == DimensionUnit.IN;

        // Mixed input units are converted to one consistent provider system without floating-point
        // errors.
        BigDecimal weight = imperial
                ? (parcel.weightUnit() == WeightUnit.LB
                        ? parcel.weight()
                        : parcel.weight()
                                .divide(POUNDS_TO_KILOGRAMS, 12, RoundingMode.HALF_UP)
                                .stripTrailingZeros())
                : (parcel.weightUnit() == WeightUnit.KG
                        ? parcel.weight()
                        : parcel.weight().multiply(POUNDS_TO_KILOGRAMS).stripTrailingZeros());

        return new RateApiRequest(
                toAddress(intent.sender()),
                toAddress(intent.recipient()),
                parcel.packageType(),
                List.of(
                        new LineItemPayload(
                                parcel.length(),
                                parcel.width(),
                                parcel.height(),
                                weight)),
                imperial ? "IMPERIAL" : "METRIC",
                intent.shippingDate().toString(),
                collectionSeconds);
    }

    private List<RateOffer> offers(RateApiResponse response) {
        if (response == null) {
            throw ProviderFailures.invalidResponse();
        }
        List<RateOffer> result = new ArrayList<>(response.availableRates().size());
        long expiresAt = clock.millis() + quoteLifetimeSeconds * 1000L;
        for (RateItem item : response.availableRates()) {
            if (!"CAD".equals(item.totalCharge().currency())) {
                throw ProviderFailures.invalidResponse();
            }
            Integer days = item.transitDays() != null
                            && (item.transitUnit() == null
                                    || "Days".equalsIgnoreCase(item.transitUnit()))
                    ? item.transitDays()
                    : null;
            result.add(
                    new RateOffer(
                            properties.providerId(),
                            properties.accountAlias(),
                            item.quoteId(),
                            item.carrierId(),
                            item.carrierName(),
                            item.serviceId(),
                            item.serviceName(),
                            item.totalCharge().amount(),
                            item.totalCharge().currency(),
                            days,
                            expiresAt));
        }
        return result;
    }

    private static AddressPayload toAddress(ShippingAddress address) {
        return new AddressPayload(
                address.contactName(),
                address.companyName(),
                address.addressLine1(),
                address.addressLine2(),
                address.city(),
                address.province(),
                address.postalCode(),
                address.countryCode(),
                address.phone(),
                address.email(),
                address.residential());
    }

    private static long after(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }
}
