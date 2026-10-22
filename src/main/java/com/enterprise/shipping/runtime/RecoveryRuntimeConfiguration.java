package com.enterprise.shipping.runtime;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;

/** Current deployment places both owned tables in one DB. Only this composition layer knows the binding. */
@Configuration(proxyBeanMethods = false)
public class RecoveryRuntimeConfiguration {
    @Bean(name = "reconciliationClock")
    Clock reconciliationClock(@Qualifier("bookingClock") Clock clock) { return clock; }
    @Bean(name = "reconciliationDataSource", destroyMethod = "")
    DataSource reconciliationDataSource(@Qualifier("bookingDataSource") DataSource bookingDataSource) { return bookingDataSource; }
}
