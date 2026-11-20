package com.enterprise.shipping.provider.sandbox.shipments;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringValueResolver;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
public class SandboxShipmentsHttpConfiguration {
    @Bean
    public SandboxShipmentsHttpService sandboxShipmentsHttpService(
            @Qualifier("sandboxWebClient") WebClient client,
            @Qualifier("sandboxHttpRouteResolver") StringValueResolver routes) {
        var adapter = WebClientAdapter.create(client.mutate()
                .defaultStatusHandler(status -> status.value() != 200 && status.value() != 201,
                        ClientResponse::createException)
                .build());
        return HttpServiceProxyFactory.builderFor(adapter).embeddedValueResolver(routes)
                .build().createClient(SandboxShipmentsHttpService.class);
    }
}
