package com.enterprise.shipping.demo.booking;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Server-side fault injection. Tests can change it without putting simulation controls into API payloads. */
public final class DemoBookingBehavior {
    private final AtomicReference<DemoBookingProperties.Mode> mode;
    public DemoBookingBehavior(DemoBookingProperties.Mode initial) { mode = new AtomicReference<>(initial); }
    public DemoBookingProperties.Mode mode() { return mode.get(); }
    public void setMode(DemoBookingProperties.Mode value) { mode.set(Objects.requireNonNull(value)); }
}
