package com.enterprise.shipping.provider.sandbox.labels;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringValueResolver;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration(proxyBeanMethods = false)
public class SandboxLabelsHttpConfiguration {
    @Bean
    public SandboxLabelsHttpService sandboxLabelsHttpService(
            @Qualifier("sandboxWebClient") WebClient client,
            @Qualifier("sandboxHttpRouteResolver") StringValueResolver routes) {
        var adapter = WebClientAdapter.create(client.mutate()
                .defaultStatusHandler(status -> status.value() == 202 || !status.is2xxSuccessful(),
                        ClientResponse::createException)
                .build());
        return HttpServiceProxyFactory.builderFor(adapter).embeddedValueResolver(routes)
                .build().createClient(SandboxLabelsHttpService.class);
    }
}
