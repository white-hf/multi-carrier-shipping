package com.enterprise.shipping.shipping.config;

import com.enterprise.shipping.provider.sandbox.SandboxProviderProperties;
import com.enterprise.shipping.provider.sandbox.label.LabelOperation;
import com.enterprise.shipping.provider.sandbox.rate.RateOperation;
import com.enterprise.shipping.provider.sandbox.shipment.CreateOperation;
import com.enterprise.shipping.shipping.application.BookingService;
import com.enterprise.shipping.shipping.application.QuoteService;
import com.enterprise.shipping.shipping.application.RecoveryOperations;
import com.enterprise.shipping.shipping.contract.ShipmentRecovery;
import com.enterprise.shipping.shipping.infrastructure.provider.SandboxLabelAdapter;
import com.enterprise.shipping.shipping.infrastructure.provider.SandboxRateAdapter;
import com.enterprise.shipping.shipping.infrastructure.provider.SandboxShipmentAdapter;
import com.enterprise.shipping.shipping.persistence.BookingRepository;
import com.enterprise.shipping.shipping.port.LabelProvider;
import com.enterprise.shipping.shipping.port.RateProvider;
import com.enterprise.shipping.shipping.port.ShipmentProvider;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition root for the shipping feature: ports are bound to provider adapters here only. */
@Configuration(proxyBeanMethods = false)
public class ShippingConfiguration {
    @Bean
    BookingRepository bookingRepository(DataSource source) {
        return new BookingRepository(new JdbcTemplate(source));
    }

    @Bean
    RateProvider rateProvider(RateOperation operation) {
        return new SandboxRateAdapter(operation);
    }

    @Bean
    ShipmentProvider shipmentProvider(CreateOperation operation) {
        return new SandboxShipmentAdapter(operation);
    }

    @Bean
    LabelProvider labelProvider(LabelOperation operation) {
        return new SandboxLabelAdapter(operation);
    }

    @Bean
    QuoteService quoteService(
            RateProvider provider,
            Clock clock,
            MeterRegistry metrics,
            @Value("${shipping.provider.rate-timeout:90s}") Duration rateTimeout,
            @Value("${shipping.business-timezone:America/Toronto}") String zone) {
        return new QuoteService(provider, clock, rateTimeout, ZoneId.of(zone), metrics);
    }

    @Bean
    BookingService bookingService(
            BookingRepository repository,
            ShipmentProvider shipments,
            LabelProvider labels,
            SandboxProviderProperties properties,
            Validator validator,
            Clock clock,
            MeterRegistry metrics,
            @Value("${shipping.provider.create-timeout:60s}") Duration createTimeout,
            @Value("${shipping.business-timezone:America/Toronto}") String zone) {
        return new BookingService(
                repository,
                validator,
                shipments,
                labels,
                clock,
                createTimeout,
                metrics,
                properties.providerId(),
                properties.accountAlias(),
                ZoneId.of(zone));
    }

    @Bean
    ShipmentRecovery shipmentRecovery(
            BookingRepository repository,
            BookingService bookings) {
        return new RecoveryOperations(repository, bookings);
    }
}
