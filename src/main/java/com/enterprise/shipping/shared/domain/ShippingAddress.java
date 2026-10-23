package com.enterprise.shipping.shared.domain;

import jakarta.validation.constraints.*;
import java.util.Locale;

/** An immutable fulfillment snapshot, not a mutable address-book entry. */
public record ShippingAddress(
        @NotBlank @Size(max=100) String contactName, @Size(max=100) String companyName,
        @NotBlank @Size(max=150) String addressLine1, @Size(max=150) String addressLine2,
        @NotBlank @Size(max=80) String city,
        @NotBlank @Pattern(regexp="BC|AB|SK|MB|ON|QC|NB|NS|PE|NL|YT|NT|NU") String province,
        @NotBlank @Pattern(regexp="[ABCEGHJ-NPRSTVXY][0-9][ABCEGHJ-NPRSTV-Z] ?[0-9][ABCEGHJ-NPRSTV-Z][0-9]") String postalCode,
        @NotBlank @Pattern(regexp="CA") String countryCode,
        @NotBlank @Size(max=32) @Pattern(regexp="[+0-9() .-]{7,32}") String phone,
        @Email @Size(max=254) String email, boolean residential) {
    public ShippingAddress {
        contactName = clean(contactName); companyName = clean(companyName);
        addressLine1 = clean(addressLine1); addressLine2 = clean(addressLine2); city = clean(city);
        province = upper(province); postalCode = upper(postalCode); countryCode = upper(countryCode);
        phone = clean(phone); email = clean(email);
    }
    private static String clean(String value) { return value == null ? null : value.strip(); }
    private static String upper(String value) { return value == null ? null : value.strip().toUpperCase(Locale.ROOT); }
}
