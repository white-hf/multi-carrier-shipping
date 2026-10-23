package com.enterprise.shipping.booking.config;

import com.enterprise.shipping.booking.port.*;
import com.enterprise.shipping.booking.application.*;
import com.enterprise.shipping.booking.infrastructure.persistence.JdbcShipmentStore;
import com.enterprise.shipping.quote.contract.QuoteSelectionPort;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
@Profile("workflow")
public class WorkflowBookingConfiguration {
    @Bean ShipmentWorkflowObserver shipmentWorkflowObserver(io.micrometer.core.instrument.MeterRegistry metrics){
        return new com.enterprise.shipping.booking.infrastructure.monitoring.MicrometerShipmentWorkflowObserver(metrics);
    }
    @Bean ShipmentStore shipmentStore(DataSource source){return new JdbcShipmentStore(new JdbcTemplate(source));}
    @Bean ShipmentApplicationService shipmentApplicationService(ShipmentStore store,QuoteSelectionPort quotes,ShipmentProviderPort provider,LabelProviderPort labels,Clock clock,@Value("${shipping.provider.create-timeout}") Duration timeout,ShipmentWorkflowObserver observer){
        return new ShipmentApplicationService(store,quotes,provider,labels,clock,timeout,observer);
    }
    @Bean ShipmentRecoveryService shipmentRecoveryService(ShipmentStore store){return new ShipmentRecoveryService(store);}
}
