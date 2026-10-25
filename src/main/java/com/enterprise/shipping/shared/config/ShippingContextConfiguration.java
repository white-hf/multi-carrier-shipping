package com.enterprise.shipping.shared.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared time source; authentication policy belongs to the auth module. */
@Configuration(proxyBeanMethods = false)
public class ShippingContextConfiguration {

    @Bean
    Clock shippingClock() {
        return Clock.systemUTC();
    }
}
