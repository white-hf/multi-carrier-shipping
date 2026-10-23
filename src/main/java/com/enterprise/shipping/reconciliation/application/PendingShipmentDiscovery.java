package com.enterprise.shipping.reconciliation.application;

import com.enterprise.shipping.booking.contract.ShipmentRecoveryPort;
import com.enterprise.shipping.reconciliation.port.PendingTaskStore;
import com.enterprise.shipping.reconciliation.port.PendingRecoveryObserver;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.*;

/** Independent recovery boundary. No task is written on normal successful shipment creation. */
public final class PendingShipmentDiscovery {
    private static final Logger log=LoggerFactory.getLogger(PendingShipmentDiscovery.class);
    private final PendingRecoveryObserver observer;
    private ShipmentRecoveryPort.Cursor cursor=ShipmentRecoveryPort.Cursor.START;
    private final ShipmentRecoveryPort bookings;private final PendingTaskStore tasks;private final Clock clock;
    public PendingShipmentDiscovery(ShipmentRecoveryPort bookings,PendingTaskStore tasks,Clock clock,PendingRecoveryObserver observer){this.bookings=bookings;this.tasks=tasks;this.clock=clock;this.observer=observer;}
    @Scheduled(fixedDelayString="${shipping.recovery.discovery-interval-ms:15000}")
    public void discover(){
        long start=System.nanoTime();int discovered=0,created=0;long age=0;boolean failed=false;
        try {
            var page=bookings.discover(clock.millis(),cursor,100);
            discovered=page.candidates().size();
            for(var candidate:page.candidates()){if(tasks.registerManualReview(candidate,clock.millis()))created++;age=Math.max(age,clock.millis()-candidate.deadline());}
            // Advance only after task writes succeed; rescan the page after any interruption.
            cursor=page.next();
        }
        catch(RuntimeException unavailable){failed=true;log.warn("Recovery discovery interrupted type={}",unavailable.getClass().getSimpleName());}
        finally{observer.scan(discovered,created,age,System.nanoTime()-start,failed);}
    }
}
