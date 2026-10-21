package com.enterprise.shipping.booking.port;

import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.contracts.recovery.Recovery.*;
import java.util.List;
import java.util.Optional;

/** Business-state operations only. Each conditional write commits before returning. */
public interface BookingStore {
    Optional<BookingRecord> find(BookingOwner owner, String idempotencyKey);
    Optional<BookingRecord> findById(BookingOwner owner, String bookingId);
    Optional<BookingRecord> findForRecovery(Reference reference);
    boolean insertClaim(BookingRecord candidate);
    boolean saveOutcome(BookingRecord expected, ShipmentBooking booking, long now);
    List<Candidate> discoverUnresolved(long now, Cursor after, int limit);
}
