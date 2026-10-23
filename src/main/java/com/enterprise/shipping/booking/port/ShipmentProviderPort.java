package com.enterprise.shipping.booking.port;

/** Only confirmed success and explicit pre-acceptance rejection are terminal outcomes. */
public interface ShipmentProviderPort {
    Outcome create(Submission submission);
    record Submission(String providerId,String accountAlias,String providerQuoteId) {}
    record Outcome(String shipmentId,String trackingNumber,String labelUrl,boolean rejected) {
        public static Outcome rejection() { return new Outcome(null,null,null,true); }
    }
}
