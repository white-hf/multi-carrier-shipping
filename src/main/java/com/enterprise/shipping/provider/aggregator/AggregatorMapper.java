package com.enterprise.shipping.provider.aggregator;

import com.enterprise.shipping.provider.aggregator.api.dto.AddressPayload;
import com.enterprise.shipping.provider.aggregator.api.dto.CreateShipmentResponse;
import com.enterprise.shipping.provider.aggregator.api.dto.LineItemPayload;
import com.enterprise.shipping.provider.aggregator.api.dto.RateApiRequest;
import com.enterprise.shipping.provider.aggregator.api.dto.RateApiResponse;
import com.enterprise.shipping.provider.aggregator.api.dto.RateItem;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.CreationOutcome;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.RateOffer;
import com.enterprise.shipping.shared.domain.DimensionUnit;
import com.enterprise.shipping.shared.domain.ShippingAddress;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.domain.WeightUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Translates between domain shipping intent, typed aggregator DTOs, and neutral provider models.
 */
public final class AggregatorMapper {

    private static final BigDecimal POUNDS_TO_KILOGRAMS = new BigDecimal("0.45359237");
    private final int collectionSeconds;

    public AggregatorMapper(int collectionSeconds) {
        this.collectionSeconds = collectionSeconds;
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

    public List<RateOffer> toRateOffers(
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

    public CreationOutcome toCreationOutcome(
            CreateShipmentResponse response, Predicate<String> allowedUrl) {
        if (response.rejected()) {
            return CreationOutcome.rejection();
        }
        String tracking =
                response.trackingNumbers().isEmpty()
                        ? null
                        : response.trackingNumbers().get(0);
        String url = response.labelUrl();
        return new CreationOutcome(
                response.shipmentId(),
                tracking,
                allowedUrl.test(url) ? url : null,
                false);
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
