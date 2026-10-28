package com.enterprise.shipping.booking.infrastructure.monitoring;

import com.enterprise.shipping.booking.port.ShippingBookingObserver;
import com.enterprise.shipping.shared.infrastructure.monitoring.SafeMetrics;
import io.micrometer.core.instrument.MeterRegistry;

public final class MicrometerShippingBookingObserver implements ShippingBookingObserver {

    private final MeterRegistry registry;

    public MicrometerShippingBookingObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(Operation operation, Outcome outcome, long elapsed) {
        SafeMetrics.record(
                registry,
                "shipping.booking",
                elapsed,
                "operation",
                operation.name(),
                "outcome",
                outcome.name());
    }
}
