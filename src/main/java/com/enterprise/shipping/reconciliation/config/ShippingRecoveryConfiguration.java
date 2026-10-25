package com.enterprise.shipping.reconciliation.config;

import com.enterprise.shipping.booking.contract.ShipmentRecoveryPort;
import com.enterprise.shipping.reconciliation.application.PendingShipmentDiscovery;
import com.enterprise.shipping.reconciliation.infrastructure.monitoring.MicrometerPendingRecoveryObserver;
import com.enterprise.shipping.reconciliation.infrastructure.persistence.JdbcPendingTaskStore;
import com.enterprise.shipping.reconciliation.port.PendingRecoveryObserver;
import com.enterprise.shipping.reconciliation.port.PendingTaskStore;
import com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ShippingRecoveryConfiguration {

    @Bean
    PendingRecoveryObserver pendingRecoveryObserver(MeterRegistry metrics) {
        return new MicrometerPendingRecoveryObserver(metrics);
    }

    @Bean
    PendingTaskStore pendingTaskStore(DataSource source) {
        return new JdbcPendingTaskStore(new JdbcTemplate(source));
    }

    @Bean
    PendingShipmentDiscovery pendingShipmentDiscovery(
            ShipmentRecoveryPort bookings,
            PendingTaskStore tasks,
            Clock clock,
            PendingRecoveryObserver observer,
            RecoveryCapabilityPort capabilities) {
        return new PendingShipmentDiscovery(bookings, tasks, clock, observer, capabilities);
    }
}
