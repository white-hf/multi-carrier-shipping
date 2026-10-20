package com.enterprise.shipping.booking.config;

import com.enterprise.shipping.shared.domain.ShippingMarketPolicy;
import com.enterprise.shipping.booking.application.*;
import com.enterprise.shipping.booking.infrastructure.monitoring.MicrometerBookingObserver;
import com.enterprise.shipping.booking.infrastructure.persistence.*;
import com.enterprise.shipping.booking.port.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Business composition root; external carrier and owner implementations are supplied by infrastructure. */
@Configuration
@ConditionalOnProperty(name = "shipping.booking.enabled", havingValue = "true")
@EnableConfigurationProperties(BookingProperties.class)
@EnableScheduling
@EnableTransactionManagement
public class BookingConfiguration {

    @Bean(destroyMethod = "close") @Primary
    HikariDataSource bookingDataSource(BookingProperties p) {
        return SqliteDataSources.open(p.databasePath(), "booking-db", p.databasePoolSize(), p.databaseBusyTimeoutMs(),
                "booking/schema-v1.sql");
    }

    @Bean
    PlatformTransactionManager bookingTransactionManager(@Qualifier("bookingDataSource") DataSource source) {
        return new DataSourceTransactionManager(source);
    }

    @Bean
    BookingStore bookingStore(@Qualifier("bookingDataSource") DataSource source) {
        return new JdbcBookingStore(new JdbcTemplate(source));
    }

    @Bean BookingJson bookingJson(ObjectMapper mapper) { return new BookingJson(mapper); }
    @Bean Clock bookingClock() { return Clock.systemUTC(); }
    @Bean BookingObserver bookingObserver(MeterRegistry registry) { return new MicrometerBookingObserver(registry); }

    @Bean
    BookingService bookingService(BookingStore store, CarrierBookingPort carrier, BookingJson json,
            BookingProperties properties, Clock clock, BookingObserver observer, ShippingMarketPolicy market) {
        return new BookingService(store, carrier, json, properties, clock, observer, market);
    }

    @Bean
    BookingReconciliationWorker bookingReconciliationWorker(BookingStore store, CarrierBookingPort carrier,
            BookingJson json, BookingProperties properties, Clock clock, BookingObserver observer) {
        return new BookingReconciliationWorker(store, carrier, json, properties, clock, observer);
    }

    @Bean
    @ConditionalOnProperty(name = "shipping.booking.reconciliation-enabled", havingValue = "true", matchIfMissing = true)
    BookingReconciliationScheduler bookingReconciliationScheduler(BookingReconciliationWorker worker) {
        return new BookingReconciliationScheduler(worker);
    }

}
