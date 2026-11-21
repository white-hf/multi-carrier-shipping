package com.enterprise.shipping.provider.sandbox.shipment;

import com.enterprise.shipping.provider.sandbox.ProviderRouteResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
public class ShipmentHttpConfiguration {
    @Bean
    public ShipmentHttpClient shipmentHttpClient(
            WebClient sandboxWebClient,
            @Value("${shipping.provider.shipments-path:/rest/shipments}") String shipmentsPath) {
        var routes = ProviderRouteResolver.shipments(shipmentsPath);
        var client = sandboxWebClient.mutate()
                .defaultStatusHandler(
                        status -> status.value() != 200 && status.value() != 201,
                        ClientResponse::createException)
                .build();
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client))
                .embeddedValueResolver(routes)
                .build()
                .createClient(ShipmentHttpClient.class);
    }
}
