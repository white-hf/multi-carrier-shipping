package com.enterprise.shipping.booking.port;

import com.enterprise.shipping.booking.domain.BookingCommand;
import com.enterprise.shipping.booking.domain.CarrierReceipt;
import java.util.Optional;

/** Remote side effects must never participate in a local database transaction. */
public interface CarrierBookingPort {
    CarrierReceipt submit(String customerReference, BookingCommand command);

    /** Empty means not found now, not proof that a previously submitted booking cannot appear later. */
    Optional<CarrierReceipt> inquire(String customerReference);

    final class Rejected extends RuntimeException {
        public Rejected() { super("CARRIER_REJECTED"); }
    }

    final class UnknownOutcome extends RuntimeException {
        public UnknownOutcome(Throwable cause) { super("CARRIER_OUTCOME_UNKNOWN", cause); }
    }
}
