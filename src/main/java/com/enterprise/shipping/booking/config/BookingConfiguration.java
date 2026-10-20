package com.enterprise.shipping.booking.config;

import com.enterprise.shipping.booking.application.*;
import com.enterprise.shipping.booking.infrastructure.carrier.HttpCarrierBookingAdapter;
import com.enterprise.shipping.demo.booking.SqliteDemoCarrier;
import com.enterprise.shipping.booking.domain.BookingOwner;
import com.enterprise.shipping.booking.infrastructure.monitoring.MicrometerBookingObserver;
import com.enterprise.shipping.booking.infrastructure.persistence.*;
import com.enterprise.shipping.booking.port.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.shipping.shared.infrastructure.http.CarrierHttpClientFactory;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

/** Explicit composition root keeps the local booking demo optional and dependencies visible. */
@Configuration
@Profile("demo")
@ConditionalOnProperty(name = "shipping.booking.enabled", havingValue = "true")
@EnableConfigurationProperties(BookingProperties.class)
@EnableScheduling
@EnableTransactionManagement
public class BookingConfiguration {
    private static final int CARRIER_CONNECTIONS = 8;

    @Bean(destroyMethod = "close") @Primary
    HikariDataSource bookingDataSource(BookingProperties p) {
        return SqliteDataSources.open(p.databasePath(), "booking-db", p.databasePoolSize(), p.databaseBusyTimeoutMs(),
                "booking/schema-v1.sql");
    }

    @Bean(destroyMethod = "close")
    HikariDataSource demoCarrierDataSource(BookingProperties p) {
        return SqliteDataSources.open(p.carrierDatabasePath(), "demo-carrier-db", p.databasePoolSize(),
                p.databaseBusyTimeoutMs(), "booking/carrier-schema-v1.sql");
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
    @Bean BookingOwner bookingOwner(BookingProperties p) { return new BookingOwner("MERCHANT", p.demoOwnerId()); }
    @Bean BookingObserver bookingObserver(MeterRegistry registry) { return new MicrometerBookingObserver(registry); }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider bookingCarrierPool() {
        return CarrierHttpClientFactory.pool("booking-carrier", CARRIER_CONNECTIONS);
    }

    @Bean
    CarrierBookingPort bookingCarrier(@Qualifier("bookingCarrierPool") ConnectionProvider pool, BookingProperties p) {
        // Side-effecting submissions must never receive an automatic transport retry.
        WebClient client = CarrierHttpClientFactory.client(pool, p.carrierBaseUrl(), p.carrierTimeoutMs(),
                CarrierHttpClientFactory.TransportRetry.DISABLED);
        return new HttpCarrierBookingAdapter(client, Duration.ofMillis(p.carrierTimeoutMs()));
    }

    @Bean
    BookingService bookingService(BookingStore store, CarrierBookingPort carrier, BookingJson json,
            BookingProperties properties, Clock clock, BookingObserver observer) {
        return new BookingService(store, carrier, json, properties, clock, observer);
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

    @Bean
    SqliteDemoCarrier sqliteDemoCarrier(@Qualifier("demoCarrierDataSource") DataSource source,
            BookingJson canonical, ObjectMapper mapper) {
        return new SqliteDemoCarrier(new JdbcTemplate(source), canonical, mapper);
    }
}
