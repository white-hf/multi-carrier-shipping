package com.enterprise.shipping.booking.config;

import com.enterprise.shipping.booking.application.ShipmentApplicationService;
import com.enterprise.shipping.booking.application.ShipmentRecoveryService;
import com.enterprise.shipping.booking.infrastructure.monitoring.MicrometerShippingBookingObserver;
import com.enterprise.shipping.booking.infrastructure.persistence.JdbcShipmentStore;
import com.enterprise.shipping.booking.port.LabelProviderPort;
import com.enterprise.shipping.booking.port.ShipmentProviderPort;
import com.enterprise.shipping.booking.port.ShipmentStore;
import com.enterprise.shipping.booking.port.ShippingBookingObserver;
import com.enterprise.shipping.quote.contract.QuoteSelectionPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class ShippingBookingConfiguration {

    @Bean
    ShippingBookingObserver shippingBookingObserver(MeterRegistry metrics) {
        return new MicrometerShippingBookingObserver(metrics);
    }

    @Bean
    ShipmentStore shipmentStore(DataSource source) {
        return new JdbcShipmentStore(new JdbcTemplate(source));
    }

    @Bean
    ShipmentApplicationService shipmentApplicationService(
            ShipmentStore store,
            QuoteSelectionPort quotes,
            ShipmentProviderPort provider,
            LabelProviderPort labels,
            Clock clock,
            @Value("${shipping.provider.create-timeout}") Duration timeout,
            ShippingBookingObserver observer) {
        return new ShipmentApplicationService(store, quotes, provider, labels, clock, timeout, observer);
    }

    @Bean
    ShipmentRecoveryService shipmentRecoveryService(ShipmentStore store) {
        return new ShipmentRecoveryService(store);
    }
}
