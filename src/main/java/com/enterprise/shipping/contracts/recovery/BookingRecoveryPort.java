package com.enterprise.shipping.contracts.recovery;

import com.enterprise.shipping.contracts.recovery.Recovery.*;

/** Booking-owned discovery and idempotent outcome application; replace local calls with RPC when extracted. */
public interface BookingRecoveryPort {
    Page discover(Cursor after, int limit);
    ApplyResult apply(Observation observation);
}
