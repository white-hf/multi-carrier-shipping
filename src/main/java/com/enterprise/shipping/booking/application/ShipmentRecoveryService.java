package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.contract.ShipmentRecoveryPort;
import com.enterprise.shipping.booking.port.ShipmentStore;
import java.util.List;

public final class ShipmentRecoveryService implements ShipmentRecoveryPort {
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
}
