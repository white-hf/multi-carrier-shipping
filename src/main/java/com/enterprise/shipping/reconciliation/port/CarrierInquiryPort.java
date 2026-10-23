package com.enterprise.shipping.reconciliation.port;

import com.enterprise.shipping.booking.contract.recovery.Recovery.*;
import java.util.Optional;

/** Read-only carrier lookup. An absent result never authorizes another submission. */
public interface CarrierInquiryPort {
    Optional<Evidence> inquire(Reference reference, long timeoutMs);
}
