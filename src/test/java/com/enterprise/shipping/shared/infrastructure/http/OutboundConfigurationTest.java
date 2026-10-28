package com.enterprise.shipping.shared.infrastructure.http;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(OutboundConfiguration.class)
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void bindsTemplateDependencyAndGroupOverridesThroughSpring() {
        context.withPropertyValues(
                        "shipping.outbound.templates.slow.timeout=90s",
                        "shipping.outbound.dependencies.carrier.template=slow",
                        "shipping.outbound.dependencies.carrier.concurrency=4",
                        "shipping.outbound.dependencies.carrier.groups.auxiliary.concurrency=1",
                        "shipping.outbound.dependencies.carrier.groups.auxiliary.timeout=10s")
                .run(
                        application -> {
                            assertThat(application).hasNotFailed();
                            var policy =
                                    application
                                            .getBean(OutboundProperties.class)
                                            .resolve("carrier");
                            assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(90));
                            assertThat(policy.concurrency()).isEqualTo(4);
                            assertThat(policy.groups().get("auxiliary").concurrency()).isEqualTo(1);
                            assertThat(policy.groups().get("auxiliary").timeout())
                                    .isEqualTo(Duration.ofSeconds(10));
                        });
    }

    @Test
    void rejectsMisspelledKeysInsteadOfSilentlyUsingDefaults() {
        context.withPropertyValues("shipping.outbound.defaults.concurency=4")
                .run(application -> assertThat(application).hasFailed());
    }
}
