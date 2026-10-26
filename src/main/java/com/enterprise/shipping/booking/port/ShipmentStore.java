package com.enterprise.shipping.booking.port;

import com.enterprise.shipping.quote.contract.QuoteSelectionPort.Selection;
import com.enterprise.shipping.booking.domain.*;
import java.util.*;

/** Each mutation commits before returning; this store owns no quote or recovery tables. */
public interface ShipmentStore {
    record Record(String userId,String id,String key,String hash,Selection selection,
            BookingStatus status,String providerShipmentId,String trackingNumber,String labelUrl,String labelStatus,
            String errorCode,long deadline,long version) {
        public ShipmentBooking restore() {
            var receipt=providerShipmentId==null?null:new CarrierReceipt("booking-"+id,providerShipmentId,trackingNumber,labelUrl);
            return ShipmentBooking.restore(new BookingId(id),selection.carrierId(),"booking-"+id,status,receipt,errorCode);
        }
    }
    Optional<Record> byKey(String userId,String key);
    Optional<Record> byRequest(String userId,String requestId);
    Optional<Record> byId(String userId,String id);
    boolean claim(Record record,long now);
    /** Acquires the next submission version only for a known-unsent, unexpired booking. */
    boolean claimRetry(Record expected,long now,long deadline);
    boolean outcome(Record expected,ShipmentBooking booking,long now);
    void label(String userId,String id,String url,long now);
    java.util.List<Record> overdue(long now,long afterDeadline,String afterId,int limit);
    boolean markUnknown(Record expected,long now);
}
