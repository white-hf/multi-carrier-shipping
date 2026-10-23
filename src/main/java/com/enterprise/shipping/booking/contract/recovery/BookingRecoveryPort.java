package com.enterprise.shipping.booking.contract.recovery;

import com.enterprise.shipping.booking.contract.recovery.Recovery.*;

/** Booking-owned discovery and idempotent outcome application; replace local calls with RPC when extracted. */
public interface BookingRecoveryPort {
    Page discover(Cursor after, int limit);
    ApplyResult apply(Observation observation);
}
