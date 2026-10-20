package com.enterprise.shipping.shared.domain;

/** Optional safe diagnostic code for carrier errors; never includes an upstream response body. */
public interface CarrierFailure {
    String diagnosticCode();
}
