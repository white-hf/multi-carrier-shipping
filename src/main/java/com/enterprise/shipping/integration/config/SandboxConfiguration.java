package com.enterprise.shipping.integration.config;

import com.enterprise.shipping.integration.SandboxShippingAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods=false)
@Profile("workflow")
@EnableConfigurationProperties(ProviderProperties.class)
public class SandboxConfiguration {
    @Bean SandboxShippingAdapter sandboxShippingAdapter(ProviderProperties properties,ObjectMapper mapper,Clock clock){
        return new SandboxShippingAdapter(properties,mapper,clock);
    }
}
