package com.enterprise.shipping.provider.sandbox.rates;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
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
import com.enterprise.shipping.shared.infrastructure.http.OutboundClient;
import com.enterprise.shipping.shared.infrastructure.http.OutboundEndpoint;
import com.enterprise.shipping.shared.infrastructure.http.ResponseDecoder;
import com.enterprise.shipping.shared.infrastructure.http.StatusPolicy;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Self-contained rate-quoting API operation for the sandbox provider.
 * Owns its /rest/rates endpoint contract, unit conversion, and response mapping independently.
 */
public class SandboxRateOperation {

    private static final BigDecimal POUNDS_TO_KILOGRAMS = new BigDecimal("0.45359237");
    private static final OutboundEndpoint<RateApiRequest, RateApiResponse> ENDPOINT =
            OutboundEndpoint.post(
                            "rates",
                            "/rest/rates",
                            RateApiRequest.class,
                            RateApiResponse.class)
                    .decoder(
                            ResponseDecoder.json(RateApiResponse.class)
                                    .validate(SandboxRateOperation::validateCurrency))
                    .statusPolicy(
                            StatusPolicy.<RateApiResponse>of(200)
                                    .onExpected(404, RateApiResponse::empty)
                                    .onError(
                                            400,
                                            () ->
                                                    ShippingException.invalidInput(
                                                            "PROVIDER_REQUEST_REJECTED"))
                                    .otherwiseThrow(ProviderAccountContext::readFailure));

    private final OutboundClient outbound;
    private final ProviderAccountContext context;
    private final int collectionSeconds;
    private final int quoteLifetimeSeconds;
    private final Clock clock;

    public SandboxRateOperation(
            OutboundClient outbound,
            ProviderAccountContext context,
            int collectionSeconds,
            int quoteLifetimeSeconds,
            Clock clock) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.context = Objects.requireNonNull(context, "context");
        this.collectionSeconds = collectionSeconds;
        this.quoteLifetimeSeconds = quoteLifetimeSeconds;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.outbound.register(ENDPOINT);
    }

    @MonitorCarrierApi(carrier = "shipping-provider")
    public List<RateOffer> fetchRates(ShippingIntent intent, long deadline) {
        // 1. Convert units and invoke the self-contained /rest/rates endpoint
        RateApiResponse response = outbound.invoke(ENDPOINT, toRateRequest(intent), deadline);

        // 2. Map validated rate items into neutral rate offers
        long expiresAt = clock.millis() + quoteLifetimeSeconds * 1000L;
        return toRateOffers(response, context.providerId(), context.accountAlias(), expiresAt);
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
