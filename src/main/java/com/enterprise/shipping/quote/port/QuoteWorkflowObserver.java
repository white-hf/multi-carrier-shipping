package com.enterprise.shipping.quote.port;

/** Application outcomes only; transport timing is measured independently by AOP. */
public interface QuoteWorkflowObserver {
    enum Outcome { READY, NO_RATES, FAILED }
    void record(Outcome outcome,long elapsedNanos);
}
