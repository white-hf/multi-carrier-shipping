package com.enterprise.shipping.reconciliation;

import com.enterprise.shipping.shipping.contract.ShipmentRecovery;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Reconciliation owns its scheduling, task table and discovery cursor. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ReconciliationConfiguration {
    @Bean
    ReviewTaskRepository reviewTaskRepository(DataSource source) {
        return new ReviewTaskRepository(new JdbcTemplate(source));
    }

    @Bean
    RecoveryJob recoveryJob(
            ShipmentRecovery recovery,
            ReviewTaskRepository tasks,
            Clock clock,
            MeterRegistry metrics) {
        return new RecoveryJob(recovery, tasks, clock, metrics);
    }
}
