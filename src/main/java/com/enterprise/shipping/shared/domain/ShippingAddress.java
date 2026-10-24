package com.enterprise.shipping.shared.domain;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * Immutable fulfillment address snapshot aligned with RDBMS column boundaries.
 */
public record ShippingAddress(
        @NotBlank @Size(max = 128) String contactName,
        @Size(max = 200) String companyName,
        @NotBlank @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @NotBlank @Size(max = 128) String city,
        @NotBlank @Size(max = 64) @Pattern(regexp = "BC|AB|SK|MB|ON|QC|NB|NS|PE|NL|YT|NT|NU") String province,
        @NotBlank @Size(max = 20) @Pattern(regexp = "[ABCEGHJ-NPRSTVXY][0-9][ABCEGHJ-NPRSTV-Z] ?[0-9][ABCEGHJ-NPRSTV-Z][0-9]") String postalCode,
        @NotBlank @Pattern(regexp = "CA") String countryCode,
        @NotBlank @Size(max = 32) @Pattern(regexp = "[+0-9() .-]{7,32}") String phone,
        @Email @Size(max = 254) String email,
        boolean residential) {

    public ShippingAddress {
        contactName = cleanRequired(contactName);
        companyName = cleanOptional(companyName);
        addressLine1 = cleanRequired(addressLine1);
        addressLine2 = cleanOptional(addressLine2);
        city = cleanRequired(city);
        province = upper(province);
        postalCode = upper(postalCode);
        countryCode = upper(countryCode);
        phone = cleanRequired(phone);
        email = cleanOptional(email);
    }

    private static String cleanRequired(String value) {
        return value == null ? null : value.strip();
    }

    private static String cleanOptional(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String upper(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
