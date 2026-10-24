package com.enterprise.shipping.shared.config;

import java.time.Clock;
import org.springframework.context.annotation.*;

/** Shared time source; authentication policy belongs to the auth module. */
@Configuration(proxyBeanMethods = false)
@Profile("workflow")
public class WorkflowContextConfiguration {
    @Bean
    Clock workflowClock() {
        return Clock.systemUTC();
    }

}
