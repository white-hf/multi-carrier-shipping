package com.enterprise.shipping.booking.infrastructure.monitoring;

import com.enterprise.shipping.booking.port.ShipmentWorkflowObserver;
import com.enterprise.shipping.shared.infrastructure.monitoring.SafeMetrics;
import io.micrometer.core.instrument.MeterRegistry;

public final class MicrometerShipmentWorkflowObserver implements ShipmentWorkflowObserver {
    private final MeterRegistry registry;
    public MicrometerShipmentWorkflowObserver(MeterRegistry registry){this.registry=registry;}
    @Override public void record(Operation operation,Outcome outcome,long elapsed){
        SafeMetrics.record(registry,"shipping.workflow.booking",elapsed,"operation",operation.name(),"outcome",outcome.name());
    }
}
