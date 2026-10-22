package com.enterprise.shipping.reconciliation.config;

import com.enterprise.shipping.contracts.recovery.BookingRecoveryPort;
import com.enterprise.shipping.reconciliation.application.*;
import com.enterprise.shipping.reconciliation.infrastructure.monitoring.MicrometerReconciliationObserver;
import com.enterprise.shipping.reconciliation.infrastructure.persistence.JdbcReconciliationTaskStore;
import com.enterprise.shipping.reconciliation.port.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReconciliationProperties.class)
@EnableScheduling
public class ReconciliationConfiguration {
    @Bean ReconciliationTaskStore reconciliationTaskStore(@Qualifier("reconciliationDataSource") DataSource source) {
        new ResourceDatabasePopulator(new ClassPathResource("reconciliation/schema-v1.sql")).execute(source);
        return new JdbcReconciliationTaskStore(new JdbcTemplate(source));
    }
    @Bean ReconciliationObserver reconciliationObserver(MeterRegistry registry) { return new MicrometerReconciliationObserver(registry); }
    @Bean ReconciliationDiscovery reconciliationDiscovery(BookingRecoveryPort bookings, ReconciliationTaskStore tasks,
            ReconciliationProperties p, @Qualifier("reconciliationClock") Clock clock, ReconciliationObserver observer) {
        return new ReconciliationDiscovery(bookings, tasks, p, clock, observer);
    }
    @Bean ReconciliationWorker reconciliationWorker(ReconciliationTaskStore tasks, BookingRecoveryPort bookings,
            CarrierInquiryPort carrier, ReconciliationProperties p, @Qualifier("reconciliationClock") Clock clock, ReconciliationObserver observer) {
        return new ReconciliationWorker(tasks, bookings, carrier, p, clock, observer);
    }
    @Bean
    @ConditionalOnProperty(name = "shipping.reconciliation.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    ReconciliationScheduler reconciliationScheduler(ReconciliationDiscovery discovery, ReconciliationWorker worker) {
        return new ReconciliationScheduler(discovery, worker);
    }
}
