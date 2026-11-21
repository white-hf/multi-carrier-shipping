package com.enterprise.shipping.provider.sandbox.rate;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** Owns this API slice's proxy bean; adding another provider API does not modify other slices. */
@Configuration(proxyBeanMethods = false)
public class RateHttpConfiguration {
    @Bean
    public RateHttpClient rateHttpClient(WebClient sandboxWebClient) {
        var client = sandboxWebClient.mutate()
                .defaultStatusHandler(
                        status -> status.value() != 200,
                        ClientResponse::createException)
                .build();
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client))
                .build()
                .createClient(RateHttpClient.class);
    }
}
