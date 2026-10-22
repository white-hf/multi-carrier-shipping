package com.enterprise.shipping.shared.config;

import com.enterprise.shipping.shared.domain.ShippingMarketPolicy;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketConfiguration.Properties.class)
public class MarketConfiguration {
    @Validated
    @ConfigurationProperties("shipping.market")
    public record Properties(@NotBlank String defaultCountry, @NotBlank String comparisonCurrency,
            @NotEmpty Map<@NotBlank String, @NotBlank String> postalCodePatterns) {}

    @Bean ShippingMarketPolicy shippingMarketPolicy(Properties properties) {
        return new ShippingMarketPolicy(properties.defaultCountry(), properties.comparisonCurrency(), properties.postalCodePatterns());
    }
}
