package com.enterprise.shipping.booking.contract;

/** Why an unresolved booking cannot progress through automated recovery. */
public enum ManualReviewReason {
    INQUIRY_EXHAUSTED,
    AUTHORITATIVE_LOOKUP_UNSUPPORTED,
    AUTHORITATIVE_LOOKUP_UNVERIFIED,
    AUTOMATIC_RECOVERY_UNAVAILABLE;

    public static boolean accepts(String code) {
        for (var reason : values()) {
            if (reason.name().equals(code)) {
                return true;
            }
        }
        return false;
    }
}
