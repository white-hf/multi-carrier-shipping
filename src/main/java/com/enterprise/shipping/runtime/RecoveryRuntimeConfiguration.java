package com.enterprise.shipping.runtime;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Current deployment places both owned tables in one DB. Only this composition layer knows the binding. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"shipping.booking.enabled", "shipping.reconciliation.enabled"}, havingValue = "true")
public class RecoveryRuntimeConfiguration {
    @Bean(name = "reconciliationClock")
    java.time.Clock reconciliationClock(@Qualifier("bookingClock") java.time.Clock clock) { return clock; }
    @Bean(name = "reconciliationDataSource", destroyMethod = "")
    DataSource reconciliationDataSource(@Qualifier("bookingDataSource") DataSource bookingDataSource) { return bookingDataSource; }
}
