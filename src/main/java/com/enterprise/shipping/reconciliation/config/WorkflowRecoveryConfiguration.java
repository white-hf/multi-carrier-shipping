package com.enterprise.shipping.reconciliation.config;

import com.enterprise.shipping.booking.contract.ShipmentRecoveryPort;
import com.enterprise.shipping.reconciliation.application.PendingShipmentDiscovery;
import com.enterprise.shipping.reconciliation.port.PendingTaskStore;
import com.enterprise.shipping.reconciliation.port.PendingRecoveryObserver;
import com.enterprise.shipping.reconciliation.infrastructure.persistence.JdbcPendingTaskStore;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@Profile("workflow")
@EnableScheduling
public class WorkflowRecoveryConfiguration {
    @Bean PendingRecoveryObserver pendingRecoveryObserver(io.micrometer.core.instrument.MeterRegistry metrics){return new com.enterprise.shipping.reconciliation.infrastructure.monitoring.MicrometerPendingRecoveryObserver(metrics);}
    @Bean PendingTaskStore pendingTaskStore(DataSource source){return new JdbcPendingTaskStore(new JdbcTemplate(source));}
    @Bean PendingShipmentDiscovery pendingShipmentDiscovery(ShipmentRecoveryPort bookings,PendingTaskStore tasks,Clock clock,PendingRecoveryObserver observer){return new PendingShipmentDiscovery(bookings,tasks,clock,observer);}
}
