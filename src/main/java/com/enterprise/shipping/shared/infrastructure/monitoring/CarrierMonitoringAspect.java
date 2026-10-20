package com.enterprise.shipping.shared.infrastructure.monitoring;

import com.enterprise.shipping.shared.infrastructure.http.CarrierTransportFailures;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/**
 * Measures actual adapter attempts, separately from user-visible aggregation outcomes.
 * A deadline may produce TIMEOUT for the caller and INTERRUPTED for its cancelled transport.
 */
@Aspect
@Component
public class CarrierMonitoringAspect {
    private final MeterRegistry metrics;
    public CarrierMonitoringAspect(MeterRegistry metrics) { this.metrics = metrics; }
    @Around("@annotation(annotation)")
    public Object monitorApiCall(ProceedingJoinPoint call, MonitorCarrierApi annotation) throws Throwable {
        long start = System.nanoTime();
        String status = "SUCCESS";
        try { return call.proceed(); }
        catch (Throwable error) { status = CarrierTransportFailures.classify(error); throw error; }
        finally {
            // Bounded carrier/status labels avoid high-cardinality metrics such as request IDs.
            try {
                metrics.timer("shipping.carrier.transport.latency", "carrier", annotation.carrier(), "status", status)
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            } catch (RuntimeException recordingFailure) {
                // Observability must not change a carrier attempt's business outcome.
                org.slf4j.LoggerFactory.getLogger(CarrierMonitoringAspect.class)
                        .warn("Carrier metric recording failed type={}", recordingFailure.getClass().getSimpleName());
            }
        }
    }
}
