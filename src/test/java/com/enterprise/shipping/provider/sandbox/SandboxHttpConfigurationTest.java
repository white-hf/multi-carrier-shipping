package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.provider.core.ProviderAccountContext;
import com.enterprise.shipping.provider.sandbox.labels.LabelIdentity;
import com.enterprise.shipping.provider.sandbox.labels.SandboxLabelOperation;
import com.enterprise.shipping.provider.sandbox.labels.SandboxLabelsHttpConfiguration;
import com.enterprise.shipping.provider.sandbox.labels.SandboxLabelsHttpService;
import com.enterprise.shipping.provider.sandbox.rates.SandboxRateOperation;
import com.enterprise.shipping.provider.sandbox.rates.SandboxRatesHttpConfiguration;
import com.enterprise.shipping.provider.sandbox.rates.SandboxRatesHttpService;
import com.enterprise.shipping.provider.sandbox.shipments.CreateSubmission;
import com.enterprise.shipping.provider.sandbox.shipments.SandboxCreateOperation;
import com.enterprise.shipping.provider.sandbox.shipments.SandboxShipmentsHttpConfiguration;
import com.enterprise.shipping.provider.sandbox.shipments.SandboxShipmentsHttpService;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.http.OutboundGuard;
import com.enterprise.shipping.shared.infrastructure.http.OutboundProperties;
import com.enterprise.shipping.support.BookingFixtures;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.StringValueResolver;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxHttpConfigurationTest {
    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {"/rest/shipments", "/custom/shipments"})
    void springInjectsWorkingProxiesUsingNormalizedRoutes(String rawPath) throws Exception {
        var paths = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            paths.add(path);
            exchange.getRequestBody().readAllBytes();
            boolean label = path.endsWith("/label");
            String body = label ? "%PDF-test" : path.equals("/rest/rates")
                    ? "{\"availableRates\":[]}" : "{\"shipId\":123,\"trackingNumbers\":[\"TRACK\"]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", label ? "application/pdf" : "application/json");
            exchange.sendResponseHeaders(label || path.equals("/rest/rates") ? 200 : 201, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var registry = new OutboundClientRegistry(new OutboundProperties(), new SimpleMeterRegistry())) {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            var properties = properties(baseUrl, rawPath);
            var client = registry.registerClient("sandbox", baseUrl, builder -> {
            });
            var runner = new ApplicationContextRunner()
                    .withBean("sandboxWebClient", WebClient.class, () -> client)
                    .withBean("sandboxOutboundGuard", OutboundGuard.class, () -> registry.guard("sandbox"))
                    .withBean("sandboxAccountContext", ProviderAccountContext.class,
                            () -> new ProviderAccountContext("sandbox", "account", Set.of()))
                    .withBean(SandboxProviderProperties.class, () -> properties)
                    .withBean(Clock.class, Clock::systemUTC)
                    .withBean("sandboxHttpRouteResolver", StringValueResolver.class,
                            () -> new SandboxProviderConfig().sandboxHttpRouteResolver(properties))
                    .withUserConfiguration(SandboxRatesHttpConfiguration.class, SandboxShipmentsHttpConfiguration.class,
                            SandboxLabelsHttpConfiguration.class, SandboxRateOperation.class,
                            SandboxCreateOperation.class, SandboxLabelOperation.class);
            if (rawPath != null) {
                runner = runner.withPropertyValues("shipping.provider.shipments-path=" + rawPath);
            }
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                for (Class<?> service : List.of(SandboxRatesHttpService.class, SandboxShipmentsHttpService.class,
                        SandboxLabelsHttpService.class)) {
                    assertThat(Proxy.isProxyClass(context.getBean(service).getClass())).isTrue();
                }
                var input = BookingFixtures.command("q").shippingInput().dated(LocalDate.now());
                assertThat(context.getBean(SandboxRateOperation.class)
                        .fetchRates(input, OutboundDeadline.after(Duration.ofSeconds(3)))).isEmpty();
                var outcome = context.getBean(SandboxCreateOperation.class)
                        .create(new CreateSubmission("sandbox", "account", "quote-1"));
                assertThat(outcome.shipmentId()).isEqualTo("123");
                assertThat(outcome.rejected()).isFalse();
                assertThat(context.getBean(SandboxLabelOperation.class)
                        .fetch(new LabelIdentity("sandbox", "account", "123")).bytes())
                        .isEqualTo("%PDF-test".getBytes(StandardCharsets.UTF_8));
            });
            String expectedPath = rawPath == null || rawPath.isBlank() ? "/rest/shipments" : rawPath;
            assertThat(paths).containsExactly("/rest/rates", expectedPath, expectedPath + "/123/label");
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../shipments", "/unsafe/../shipments", "/shipments?redirect=elsewhere"})
    void invalidConfiguredRoutesStillFailBeforeSending(String path) {
        assertThatThrownBy(() -> properties("http://127.0.0.1", path))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SandboxProviderProperties properties(String baseUrl, String path) {
        return new SandboxProviderProperties(baseUrl, "stub", java.util.UUID.randomUUID().toString(),
                "sandbox", "account", path, Duration.ofSeconds(90), Duration.ofSeconds(3), Duration.ofSeconds(3),
                10, 300, Set.of());
    }
}
