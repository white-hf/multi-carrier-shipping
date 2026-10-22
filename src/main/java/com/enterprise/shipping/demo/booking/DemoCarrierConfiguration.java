package com.enterprise.shipping.demo.booking;

import com.enterprise.shipping.booking.application.BookingRequestHasher;
import com.enterprise.shipping.booking.config.BookingProperties;
import com.enterprise.shipping.booking.domain.BookingOwner;
import com.enterprise.shipping.shared.infrastructure.persistence.SqliteDataSources;
import com.enterprise.shipping.booking.port.BookingOwnerProvider;
import com.enterprise.shipping.shared.infrastructure.http.CarrierHttpClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.netty.resources.ConnectionProvider;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
@EnableConfigurationProperties(DemoBookingProperties.class)
public class DemoCarrierConfiguration {
    @Bean DemoBookingBehavior demoBookingBehavior(DemoBookingProperties p) { return new DemoBookingBehavior(p.mode()); }
    @Bean BookingOwnerProvider demoBookingOwnerProvider(DemoBookingProperties p) {
        BookingOwner owner = new BookingOwner("MERCHANT", p.ownerId());
        return principal -> owner;
    }

    @Bean(destroyMethod = "close")
    HikariDataSource demoCarrierDataSource(DemoBookingProperties demo, BookingProperties booking) {
        return SqliteDataSources.open(demo.databasePath(), "demo-carrier-db", booking.databasePoolSize(),
                booking.databaseBusyTimeoutMs(), "demo/carrier-schema-v1.sql");
    }

    @Bean SqliteDemoCarrier sqliteDemoCarrier(@Qualifier("demoCarrierDataSource") DataSource source,
            BookingRequestHasher hasher, ObjectMapper mapper) {
        return new SqliteDemoCarrier(new JdbcTemplate(source), hasher, mapper);
    }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider bookingCarrierPool() { return CarrierHttpClientFactory.pool("demo-booking", 8); }

    @Bean DemoHttpBookingAdapter demoBookingCarrier(@Qualifier("bookingCarrierPool") ConnectionProvider pool,
            DemoBookingProperties demo, BookingProperties booking) {
        var client = CarrierHttpClientFactory.client(pool, demo.baseUrl(), booking.carrierTimeoutMs(),
                CarrierHttpClientFactory.TransportRetry.DISABLED);
        return new DemoHttpBookingAdapter(client, Duration.ofMillis(booking.carrierTimeoutMs()));
    }
}
