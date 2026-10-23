package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.contract.ShipmentRecoveryPort;
import com.enterprise.shipping.booking.port.ShipmentStore;
import java.util.List;
import com.enterprise.shipping.booking.domain.BookingStatus;
import com.enterprise.shipping.booking.domain.ManualReviewReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ShipmentRecoveryService implements ShipmentRecoveryPort {
    private static final Logger log = LoggerFactory.getLogger(ShipmentRecoveryService.class);
    private final ShipmentStore store;
    public ShipmentRecoveryService(ShipmentStore store){this.store=store;}
    @Override public Page discover(long now,Cursor after,int limit){
        if(limit<1||limit>500)throw new IllegalArgumentException("Recovery batch must be 1–500");
        var rows=store.overdue(now,after.deadline(),after.bookingId(),limit);
        var candidates=rows.stream().map(row->{
            store.markUnknown(row,now);
            return new Candidate(row.userId(),row.id(),row.selection().providerId(),row.selection().accountAlias(),"booking-"+row.id(),row.deadline());
        }).toList();
        var next=rows.size()<limit?Cursor.START:new Cursor(rows.get(rows.size()-1).deadline(),rows.get(rows.size()-1).id());
        return new Page(candidates,next);
    }

    @Override
    public boolean requireManualReview(Candidate candidate, ManualReviewReason reason, long now) {
        var current = store.byId(candidate.userId(), candidate.bookingId());
        if (current.isEmpty() || current.get().status() != BookingStatus.PENDING_INQUIRY) return false;
        var row = current.get();
        if (!row.selection().providerId().equals(candidate.providerId())
                || !row.selection().accountAlias().equals(candidate.accountAlias())) {
            throw new IllegalArgumentException("Recovery provider context changed");
        }
        var booking = row.restore();
        booking.requireManualReview(reason);
        boolean applied = store.outcome(row, booking, now);
        if (applied) log.info("Shipment requires manual review bookingId={} reason={}", row.id(), reason);
        return applied;
    }
}
