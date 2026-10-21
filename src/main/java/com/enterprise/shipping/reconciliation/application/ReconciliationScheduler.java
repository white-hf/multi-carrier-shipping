package com.enterprise.shipping.reconciliation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Discovery cadence is independent of execution cadence and can be disabled for deterministic tests. */
public final class ReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);
    private final ReconciliationDiscovery discovery;
    private final ReconciliationWorker worker;
    public ReconciliationScheduler(ReconciliationDiscovery discovery, ReconciliationWorker worker) {
        this.discovery = discovery; this.worker = worker;
    }
    @Scheduled(fixedDelayString = "${shipping.reconciliation.discovery-interval-ms:30000}")
    public void discover() { safely(discovery::discoverBatch, "discovery"); }
    @Scheduled(fixedDelayString = "${shipping.reconciliation.execution-interval-ms:1000}")
    public void execute() { safely(worker::runBatch, "execution"); }
    private void safely(Runnable action, String stage) {
        try { action.run(); }
        catch (RuntimeException error) { log.error("Reconciliation stage unavailable stage={} type={}", stage, error.getClass().getSimpleName()); }
    }
}
