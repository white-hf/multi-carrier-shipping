package com.enterprise.shipping.booking.infrastructure.monitoring;

import com.enterprise.shipping.booking.domain.BookingStatus;
import com.enterprise.shipping.booking.port.BookingObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Micrometer stays outside application decisions; failed instrumentation cannot fail a booking. */
public final class MicrometerBookingObserver implements BookingObserver {
    private static final Logger log = LoggerFactory.getLogger(MicrometerBookingObserver.class);
    private final MeterRegistry registry;
    public MicrometerBookingObserver(MeterRegistry registry) { this.registry = registry; }

    @Override public void claim(boolean created) {
        record(() -> registry.counter("shipping.booking.claims", "result", created ? "CREATED" : "REPLAY").increment());
    }
    @Override public void conflict() {
        record(() -> registry.counter("shipping.booking.conflicts").increment());
    }
    @Override public void outcome(String operation, BookingStatus status, long elapsed) {
        record(() -> {
            registry.counter("shipping.booking.outcomes", "operation", operation, "status", status.name()).increment();
            registry.timer("shipping.booking.latency", "operation", operation, "status", status.name())
                    .record(elapsed, TimeUnit.NANOSECONDS);
        });
    }
    private void record(Runnable recording) {
        try { recording.run(); }
        catch (RuntimeException error) {
            log.warn("Booking telemetry unavailable type={}", error.getClass().getSimpleName());
        }
    }
}
