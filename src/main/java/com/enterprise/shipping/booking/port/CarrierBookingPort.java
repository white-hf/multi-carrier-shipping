package com.enterprise.shipping.booking.port;

import com.enterprise.shipping.booking.application.BookingCommand;
import com.enterprise.shipping.booking.domain.CarrierReceipt;

/** Remote side effects must never participate in a local database transaction. */
public interface CarrierBookingPort {
    CarrierReceipt submit(String carrierContextReference, String customerReference, BookingCommand command);

    final class Rejected extends RuntimeException {
        public Rejected() { super("CARRIER_REJECTED"); }
    }

    final class UnknownOutcome extends RuntimeException {
        public UnknownOutcome(Throwable cause) { super("CARRIER_OUTCOME_UNKNOWN", cause); }
    }
}
