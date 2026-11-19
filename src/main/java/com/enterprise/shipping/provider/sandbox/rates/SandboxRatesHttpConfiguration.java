package com.enterprise.shipping.provider.sandbox.rates;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
public class SandboxRatesHttpConfiguration {
    @Bean
    public SandboxRatesHttpService sandboxRatesHttpService(
            @Qualifier("sandboxWebClient") WebClient client) {
        var adapter = WebClientAdapter.create(client.mutate()
                .defaultStatusHandler(status -> status.value() != 200, ClientResponse::createException)
                .build());
        return HttpServiceProxyFactory.builderFor(adapter).build().createClient(SandboxRatesHttpService.class);
    }
}
