package com.enterprise.shipping.booking.domain;

import com.enterprise.shipping.shared.domain.ShipmentParcel;
import java.util.Locale;
import java.util.Objects;

/** Immutable, normalized booking intent used for hashing, persistence, and carrier submission. */
public record BookingCommand(String orderReference, String carrierId, String serviceLevel,
                             ShipmentParcel parcel, DemoScenario demoScenario) {
    public BookingCommand {
        orderReference = Objects.requireNonNull(orderReference).strip();
        carrierId = Objects.requireNonNull(carrierId).strip().toLowerCase(Locale.ROOT);
        serviceLevel = Objects.requireNonNull(serviceLevel).strip().toUpperCase(Locale.ROOT);
        Objects.requireNonNull(parcel);
        demoScenario = demoScenario == null ? DemoScenario.NORMAL : demoScenario;
    }

    public enum DemoScenario { NORMAL, TIMEOUT_AFTER_ACCEPT, REJECTED, UNAVAILABLE }

}
