package com.enterprise.shipping.provider.sandbox.rates;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
import com.enterprise.shipping.provider.sandbox.SandboxProviderProperties;
import com.enterprise.shipping.provider.sandbox.rates.dto.AddressPayload;
import com.enterprise.shipping.provider.sandbox.rates.dto.LineItemPayload;
import com.enterprise.shipping.provider.sandbox.rates.dto.RateApiRequest;
import com.enterprise.shipping.provider.sandbox.rates.dto.RateApiResponse;
import com.enterprise.shipping.provider.sandbox.rates.dto.RateItem;
import com.enterprise.shipping.shared.domain.DimensionUnit;
import com.enterprise.shipping.shared.domain.ShippingAddress;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.domain.WeightUnit;
import com.enterprise.shipping.shared.infrastructure.http.OutboundGuard;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDiagnostics;
import com.enterprise.shipping.shared.infrastructure.http.OutboundJson;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Self-contained rate-quoting API operation for the sandbox provider.
 * Owns its /rest/rates endpoint contract, unit conversion, and response mapping independently.
 */
@Component
public class SandboxRateOperation {

    private static final BigDecimal POUNDS_TO_KILOGRAMS = new BigDecimal("0.45359237");
    private final SandboxRatesHttpService http;
    private final OutboundGuard guard;
    private final ProviderAccountContext context;
    private final int collectionSeconds;
    private final int quoteLifetimeSeconds;
    private final Clock clock;

    @Autowired
    public SandboxRateOperation(
            @Qualifier("sandboxRatesHttpService") SandboxRatesHttpService http,
            @Qualifier("sandboxOutboundGuard") OutboundGuard guard,
            @Qualifier("sandboxAccountContext") ProviderAccountContext context,
            SandboxProviderProperties properties,
            Clock clock) {
        this(
                http,
                guard,
                context,
                properties.collectionSeconds(),
                properties.quoteLifetimeSeconds(),
                clock);
    }

    public SandboxRateOperation(
            SandboxRatesHttpService http,
            OutboundGuard guard,
            ProviderAccountContext context,
            int collectionSeconds,
            int quoteLifetimeSeconds,
            Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.context = Objects.requireNonNull(context, "context");
        this.collectionSeconds = collectionSeconds;
        this.quoteLifetimeSeconds = quoteLifetimeSeconds;
        this.clock = Objects.requireNonNull(clock, "clock");
        guard.registerOperation("rates");
    }

    @MonitorCarrierApi(carrier = "shipping-provider")
    public List<RateOffer> fetchRates(ShippingIntent intent, long deadline) {
        // 1. Preserve request conversion and the caller's absolute deadline.
        var request = toRateRequest(intent);
        var diagnostic = OutboundDiagnostics.call(context.providerId(), "rates", status -> switch (status) {
            case 200 -> OutboundDiagnostics.Classification.SUCCESS;
            case 404 -> OutboundDiagnostics.Classification.EXPECTED_BRANCH;
            default -> OutboundDiagnostics.Classification.DEPENDENCY_FAILURE;
        });

        // 2. Decode, validate and interpret provider status inside the circuit/permit scope.
        return guard.execute("default", "rates", deadline, effective -> {
            ResponseEntity<RateApiResponse> response;
            try {
                response = OutboundDeadline.await(http.rates(request).contextWrite(diagnostic::context), effective);
            } catch (WebClientResponseException failure) {
                return ratesForStatus(failure.getStatusCode().value());
            } catch (DecodingException failure) {
                diagnostic.protocolError();
                throw OutboundJson.invalidResponse(failure);
            }
            // Waiting/transport failures are not evidence of an invalid provider response.
            try {
                return offers(response.getBody());
            } catch (ShippingException failure) {
                diagnostic.protocolError();
                throw failure;
            }
        });
    }

    private List<RateOffer> offers(RateApiResponse response) {
        if (response == null) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }
        return toRateOffers(response, context.providerId(), context.accountAlias(),
                clock.millis() + quoteLifetimeSeconds * 1000L);
    }

    private List<RateOffer> ratesForStatus(int status) {
        if (status == 404) {
            return List.of();
        }
        if (status == 400) {
            throw ShippingException.invalidInput("PROVIDER_REQUEST_REJECTED");
        }
        throw ProviderAccountContext.readFailure(status);
    }

    public RateApiRequest toRateRequest(ShippingIntent intent) {
        var parcel = intent.parcel();
        boolean imperial = parcel.dimensionUnit() == DimensionUnit.IN;

        // Mixed input units are converted to one consistent provider system without floating-point
        // errors.
        BigDecimal weight =
                imperial
                        ? (parcel.weightUnit() == WeightUnit.LB
                                ? parcel.weight()
                                : parcel.weight()
                                        .divide(POUNDS_TO_KILOGRAMS, 12, RoundingMode.HALF_UP)
                                        .stripTrailingZeros())
                        : (parcel.weightUnit() == WeightUnit.KG
                                ? parcel.weight()
                                : parcel.weight()
                                        .multiply(POUNDS_TO_KILOGRAMS)
                                        .stripTrailingZeros());

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

    public static void validateCurrency(RateApiResponse response) {
        for (RateItem item : response.availableRates()) {
            if (!"CAD".equals(item.totalCharge().currency())) {
                throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
            }
        }
    }

    private static List<RateOffer> toRateOffers(
            RateApiResponse response,
            String providerId,
            String accountAlias,
            long expiresAt) {
        validateCurrency(response);
        List<RateOffer> result = new ArrayList<>(response.availableRates().size());
        for (RateItem item : response.availableRates()) {
            Integer days =
                    item.transitDays() != null
                                    && (item.transitUnit() == null
                                            || "Days".equalsIgnoreCase(item.transitUnit()))
                            ? item.transitDays()
                            : null;
            result.add(
                    new RateOffer(
                            providerId,
                            accountAlias,
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
}
