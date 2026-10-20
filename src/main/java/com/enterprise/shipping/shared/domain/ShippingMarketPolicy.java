package com.enterprise.shipping.shared.domain;

import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Immutable market rules shared by booking and quotes; no infrastructure or simulator dependencies. */
public final class ShippingMarketPolicy {
    private final String defaultCountry;
    private final String comparisonCurrency;
    private final Map<String, Pattern> postalPatterns;

    public ShippingMarketPolicy(String defaultCountry, String comparisonCurrency, Map<String, String> patterns) {
        this.defaultCountry = defaultCountry.strip().toUpperCase(Locale.ROOT);
        this.comparisonCurrency = Currency.getInstance(comparisonCurrency.strip().toUpperCase(Locale.ROOT)).getCurrencyCode();
        Set<String> countries = Set.of(Locale.getISOCountries());
        this.postalPatterns = patterns.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                e -> e.getKey().toUpperCase(Locale.ROOT), e -> Pattern.compile(e.getValue(), Pattern.CASE_INSENSITIVE)));
        if (!countries.containsAll(postalPatterns.keySet()) || !postalPatterns.containsKey(this.defaultCountry))
            throw new IllegalArgumentException("Default and supported countries must be valid configured ISO countries");
    }

    public void validate(ShipmentParcel parcel) {
        validatePostal(parcel.originCountryCode(), parcel.originPostalCode());
        validatePostal(parcel.destinationCountryCode(), parcel.destinationPostalCode());
    }

    private void validatePostal(String country, String postalCode) {
        Pattern pattern = postalPatterns.get(country);
        if (pattern == null || !pattern.matcher(postalCode).matches()) throw new InvalidParcelException();
    }

    public String defaultCountry() { return defaultCountry; }
    public String comparisonCurrency() { return comparisonCurrency; }
}
