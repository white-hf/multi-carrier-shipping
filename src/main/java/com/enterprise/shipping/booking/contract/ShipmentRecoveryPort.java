package com.enterprise.shipping.booking.contract;

import java.util.List;
import com.enterprise.shipping.booking.domain.ManualReviewReason;

/** Provider lookup is not verified: discovery exposes identity, never a repository or create capability. */
public interface ShipmentRecoveryPort {
    Page discover(long now,Cursor after,int limit);
    boolean requireManualReview(Candidate candidate, ManualReviewReason reason, long now);
    record Cursor(long deadline,String bookingId) { public static final Cursor START=new Cursor(-1,""); }
    record Page(List<Candidate> candidates,Cursor next) {public Page {candidates=List.copyOf(candidates);}}
    record Candidate(String userId,String bookingId,String providerId,String accountAlias,String customerReference,long deadline) {}
}
