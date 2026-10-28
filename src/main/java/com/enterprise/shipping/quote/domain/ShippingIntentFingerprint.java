package com.enterprise.shipping.quote.domain;

import com.enterprise.shipping.shared.domain.ShippingIntent;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Computes a canonical SHA-256 fingerprint of a ShippingIntent using length-prefixed fields
 * and normalized decimal scales so equivalent JSON payloads never cause false conflicts.
 */
public final class ShippingIntentFingerprint {

    private ShippingIntentFingerprint() {
    }

    public static String of(ShippingIntent intent) {
        List<String> fields = new ArrayList<>();
        for (var address : List.of(intent.sender(), intent.recipient())) {
            Collections.addAll(
                    fields,
                    address.contactName(),
                    address.companyName(),
                    address.addressLine1(),
                    address.addressLine2(),
                    address.city(),
                    address.province(),
                    address.postalCode().replace(" ", ""),
                    address.countryCode(),
                    address.phone(),
                    address.email(),
                    Boolean.toString(address.residential()));
        }

        var parcel = intent.parcel();
        Collections.addAll(
                fields,
                number(parcel.length()),
                number(parcel.width()),
                number(parcel.height()),
                number(parcel.weight()),
                parcel.dimensionUnit().name(),
                parcel.weightUnit().name(),
                parcel.packageType(),
                intent.shippingDate().toString());

        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : fields) {
                byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
