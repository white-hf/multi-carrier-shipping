package com.enterprise.shipping.runtime;

import com.enterprise.shipping.booking.infrastructure.provider.SandboxLabelAdapter;
import com.enterprise.shipping.booking.infrastructure.provider.SandboxShipmentAdapter;
import com.enterprise.shipping.provider.sandbox.labels.SandboxLabelOperation;
import com.enterprise.shipping.provider.sandbox.rates.SandboxRateOperation;
import com.enterprise.shipping.provider.sandbox.shipments.SandboxCreateOperation;
import com.enterprise.shipping.quote.infrastructure.provider.SandboxRateAdapter;
import com.enterprise.shipping.reconciliation.infrastructure.provider.SandboxRecoveryAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deployment composition wires consumer-owned ports to independent provider API operations
 * without provider-to-business dependencies.
 */
@Configuration(proxyBeanMethods = false)
public class ProviderAdapterConfiguration {

    @Bean
    SandboxRateAdapter sandboxRateAdapter(SandboxRateOperation rateOperation) {
        return new SandboxRateAdapter(rateOperation);
    }

    @Bean
    SandboxShipmentAdapter sandboxShipmentAdapter(SandboxCreateOperation createOperation) {
        return new SandboxShipmentAdapter(createOperation);
    }

    @Bean
    SandboxLabelAdapter sandboxLabelAdapter(SandboxLabelOperation labelOperation) {
        return new SandboxLabelAdapter(labelOperation);
    }

    @Bean
    SandboxRecoveryAdapter sandboxRecoveryAdapter(SandboxCreateOperation createOperation) {
        return new SandboxRecoveryAdapter(createOperation);
    }
}
