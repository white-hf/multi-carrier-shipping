package com.enterprise.shipping.quote.port;

import com.enterprise.shipping.shared.domain.ShipmentParcel;
import java.util.Objects;

/** Quote intent independent of HTTP validation and transport payloads. */
public record QuoteCommand(ShipmentParcel parcel) {
    public QuoteCommand {
        Objects.requireNonNull(parcel);
    }
}
