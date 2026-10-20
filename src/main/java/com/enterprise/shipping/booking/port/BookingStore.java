package com.enterprise.shipping.booking.port;

import com.enterprise.shipping.booking.domain.*;
import java.util.List;
import java.util.Optional;

/** Every mutation is one atomic statement committed before returning. */
public interface BookingStore {
    Optional<BookingRecord> find(BookingOwner owner, String idempotencyKey);
    Optional<BookingRecord> findById(BookingOwner owner, String bookingId);
    boolean insertClaim(BookingRecord candidate);
    boolean complete(BookingRecord owned, BookingStatus status, CarrierReceipt receipt,
                     String errorCode, String responseJson, long now);
    boolean defer(BookingRecord owned, BookingStatus status, String errorCode, long nextInquiryAt, long now);
    List<BookingRecord> findDue(long now, int limit);
    Optional<BookingRecord> acquireInquiryLease(BookingRecord candidate, String token, long now, long expiresAt);
}
