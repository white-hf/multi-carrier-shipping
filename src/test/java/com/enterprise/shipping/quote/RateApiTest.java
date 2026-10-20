package com.enterprise.shipping.quote;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@org.springframework.test.context.ActiveProfiles("demo")
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "shipping.booking.enabled=false", "shipping.engine.global-timeout-ms=1500",
        "shipping.engine.carriers.fedex.timeout-ms=1000", "shipping.engine.carriers.ups.timeout-ms=1000"})
class RateApiTest {
    static final String VALID = "{\"originPostalCode\":\"L6K 3V3\",\"destinationPostalCode\":\"M5V 2T6\",\"weight\":1,\"weightUnit\":\"KG\",\"dimensionUnit\":\"CM\",\"length\":10,\"width\":10,\"height\":10}";
    static final AtomicReference<String> fedex = new AtomicReference<>(), ups = new AtomicReference<>();
    static final AtomicReference<String> fedexRequest = new AtomicReference<>();
    static final AtomicInteger calls = new AtomicInteger(), delay = new AtomicInteger();
    static final ExecutorService workers = Executors.newCachedThreadPool();
    static final HttpServer server;
    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            for (String id : new String[]{"fedex", "ups"}) server.createContext("/" + id + "/rates", exchange -> {
                calls.incrementAndGet();
                String submitted = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (id.equals("fedex")) fedexRequest.set(submitted);
                if (id.equals("fedex")) try { Thread.sleep(delay.get()); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                String body = (id.equals("fedex") ? fedex : ups).get();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try { exchange.sendResponseHeaders(body.equals("ERROR") ? 503 : 200, bytes.length); exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            });
            server.start();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("shipping.engine.carriers.fedex.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/fedex");
        registry.add("shipping.engine.carriers.ups.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/ups");
    }
    @Autowired TestRestTemplate client;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired MeterRegistry metrics;
    @Autowired Environment environment;
    @LocalServerPort int port;
    @BeforeEach void reset() {
        breakers.getAllCircuitBreakers().forEach(b -> b.reset()); calls.set(0); delay.set(0);
        fedex.set("{\"services\":[{\"serviceType\":\"Priority\",\"totalRate\":18.50,\"currency\":\"CAD\",\"transitDays\":2}]}");
        ups.set("{\"services\":[{\"service\":\"Ground\",\"chargeAmount\":16.20,\"currency\":\"CAD\",\"deliveryDays\":3}]}");
    }
    @AfterAll static void close() { server.stop(0); workers.shutdownNow(); }
    ResponseEntity<String> post(String body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        return client.postForEntity("http://127.0.0.1:" + port + "/api/shipping/quotes", new HttpEntity<>(body, headers), String.class);
    }
    @Test void successfulComparisonIsSorted() {
        var response = post(VALID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("COMPLETE");
        assertThat(response.getBody().indexOf("16.20")).isLessThan(response.getBody().indexOf("18.50"));
    }
    @Test void imperialDimensionsAreNormalizedBeforeCarrierDispatch() {
        var response = post(VALID.replace("\"dimensionUnit\":\"CM\"", "\"dimensionUnit\":\"IN\""));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fedexRequest.get()).contains("\"lengthCm\":25.4", "\"widthCm\":25.4", "\"heightCm\":25.4")
                .doesNotContain("dimensionUnit");
    }

    @Test void missingUnknownUnitsAndConvertedOversizeAreRejectedBeforeDispatch() {
        for (String body : new String[] {
                VALID.replace(",\"dimensionUnit\":\"CM\"", ""),
                VALID.replace(",\"weightUnit\":\"KG\"", ""),
                VALID.replace("\"CM\"", "\"YARD\""),
                VALID.replace("\"KG\"", "\"OZ\""),
                VALID.replace("\"length\":10", "\"length\":500").replace("\"CM\"", "\"IN\""),
                VALID.replace("\"weight\":1", "\"weight\":3000").replace("\"KG\"", "\"LB\"")}) {
            assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(calls).hasValue(0);
    }

    @Test void invalidMeasurementsAndMalformedJsonReturn400WithoutCarrierCalls() {
        for (String body : new String[]{VALID.replace("\"weight\":1", "\"weight\":0"),
                VALID.replace("\"length\":10", "\"length\":-1"), VALID.replace("\"height\":10", "\"height\":null"), "{"}) {
            assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(calls).hasValue(0);
    }
    @Test void partialFailureDoesNotLeakInternalDetails() {
        fedex.set("ERROR");
        var response = post(VALID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("PARTIAL", "UPSTREAM_ERROR").doesNotContain("localhost", "WebClient", "/fedex/rates");
    }
    @Test void totalFailureIsNotSuccessfulComparison() {
        fedex.set("ERROR"); ups.set("ERROR");
        var response = post(VALID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).contains("\"success\":false", "FAILED");
    }
    @Test void noServicesIsDifferentFromOutage() {
        fedex.set("{\"services\":[]}"); ups.set("{\"services\":[]}");
        var response = post(VALID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("COMPLETE", "NO_SERVICES", "\"successfulQuotes\":[]");
    }
    @Test void malformedPayloadIsIsolated() {
        fedex.set("{\"services\":[{\"serviceType\":\"Priority\",\"currency\":\"CAD\"}]}");
        assertThat(post(VALID).getBody()).contains("PARTIAL", "INVALID_RESPONSE", "16.20");
    }
    @Test void realHttpTimeoutReleasesWorkerAndConnection() throws Exception {
        double previousErrors = metrics.find("shipping.carrier.transport.latency").tag("carrier", "fedex")
                .tag("status", "UPSTREAM_ERROR").timers().stream().mapToDouble(t -> t.count()).sum();
        delay.set(2500);
        var response = post(VALID);
        assertThat(response.getBody()).contains("PARTIAL", "TIMEOUT");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && (metrics.get("shipping.carrier.workers.active").tag("carrier", "fedex").gauge().value() > 0 ||
                metrics.find("reactor.netty.connection.provider.active.connections").gauges().stream().mapToDouble(g -> g.value()).sum() > 0)) Thread.sleep(10);
        assertThat(metrics.get("shipping.carrier.workers.active").tag("carrier", "fedex").gauge().value()).isZero();
        assertThat(metrics.find("shipping.carrier.transport.latency").tag("carrier", "fedex")
                .tag("status", "UPSTREAM_ERROR").timers().stream().mapToDouble(t -> t.count()).sum()).isEqualTo(previousErrors);
        assertThat(metrics.find("reactor.netty.connection.provider.active.connections").gauges()).isNotEmpty();
        assertThat(metrics.find("reactor.netty.connection.provider.active.connections").gauges().stream().mapToDouble(g -> g.value()).sum()).isZero();
    }
    @Test void operationalEndpointsAndMocksHaveLocalDemoBoundary() {
        assertThat(client.getForEntity("http://127.0.0.1:" + port + "/api/mock/fedex/rates", String.class).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        var management = new TestRestTemplate();
        String url = "http://127.0.0.1:" + environment.getProperty("local.management.port") + "/actuator/";
        assertThat(management.getForEntity(url + "metrics", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        post(VALID);
        var scrape = management.getForEntity(url + "prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody()).contains("shipping_aggregation_latency_seconds_bucket");
    }
}
