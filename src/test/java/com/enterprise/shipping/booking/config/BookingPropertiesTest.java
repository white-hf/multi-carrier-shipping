package com.enterprise.shipping.booking.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import static org.assertj.core.api.Assertions.*;

class BookingPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "carrier-id-exceeding-the-database-column-limit"})
    void invalidCarrierConfigurationFailsAtStartup(String configuredCarrier) {
        context.withPropertyValues("shipping.booking.enabled-carriers[0]=" + configuredCarrier)
                .run(application -> assertThat(application).hasFailed());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BookingProperties.class)
    static class PropertiesConfiguration {}
}
