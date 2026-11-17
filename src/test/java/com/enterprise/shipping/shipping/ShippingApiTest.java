package com.enterprise.shipping.shipping;

import com.enterprise.shipping.Application;
import com.enterprise.shipping.auth.application.SessionTokenService;
import com.enterprise.shipping.reconciliation.application.PendingShipmentDiscovery;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import com.enterprise.shipping.support.BookingFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real application, isolated SQLite and local HTTP carrier. No real carrier writes. */
@SpringBootTest(classes = {Application.class, ShippingApiTest.TestAuthDefaults.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ShippingApiTest {
    static final String FP = "fp-test-suite-default-01";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAuthDefaults {
        @Bean
        MockMvcBuilderCustomizer defaultAuth(SessionTokenService tokens) {
            return builder -> builder.defaultRequest(get("/")
                    .header("Authorization", "Bearer " + tokens.issueTokenForUser("guest-user", FP).token())
                    .header("X-Client-Fingerprint", FP));
        }
    }

    static final java.util.concurrent.ExecutorService THREADS = Executors.newCachedThreadPool();
    static final HttpServer PROVIDER;
    static final Path DB;
    static final AtomicInteger RATE_CALLS = new AtomicInteger();
    static final AtomicInteger CREATE_CALLS = new AtomicInteger();
    static final AtomicInteger LABEL_CALLS = new AtomicInteger();
    static final AtomicInteger RATE_STATUS = new AtomicInteger(200);
    static final AtomicInteger CREATE_STATUS = new AtomicInteger(201);
    static final AtomicInteger LABEL_STATUS = new AtomicInteger(202);
    static final AtomicInteger GENERATION = new AtomicInteger();
    static final AtomicReference<String> RATE_RESPONSE = new AtomicReference<>();
    static final AtomicReference<String> CREATE_RESPONSE = new AtomicReference<>();
    static final AtomicReference<String> CREATE_BODY = new AtomicReference<>();
    static final AtomicReference<String> LABEL_TYPE = new AtomicReference<>("application/json");
    static final AtomicReference<byte[]> LABEL_BODY = new AtomicReference<>("{}".getBytes(StandardCharsets.UTF_8));
    static final AtomicReference<JdbcTemplate> JDBC = new AtomicReference<>();
    static final AtomicReference<PendingShipmentDiscovery> DISCOVERY = new AtomicReference<>();
    static final AtomicReference<OutboundClientRegistry> OUTBOUND = new AtomicReference<>();

    static void normal() {
        int generation = GENERATION.incrementAndGet();
        RATE_STATUS.set(200);
        RATE_RESPONSE.set("""
                {"availableRates":[
                  {"quoteId":"provider-quote-%d","carrierId":"14","carrierName":"Carrier","serviceId":"1","serviceName":"Ground","totalCharge":{"amount":2735,"currency":"CAD"},"transitDays":2},
                  {"quoteId":"provider-express-%d","carrierId":"22","carrierName":"Loomis","serviceId":"2","serviceName":"Express","totalCharge":{"amount":3590,"currency":"CAD"},"transitDays":1,"transitUnit":"Days"}]}
                """.formatted(generation, generation));
        CREATE_STATUS.set(201);
        CREATE_RESPONSE.set("{\"shipId\":98765,\"trackingNumbers\":[\"TRK-98765\"]}");
        LABEL_STATUS.set(202);
        LABEL_TYPE.set("application/json");
        LABEL_BODY.set("{}".getBytes(StandardCharsets.UTF_8));
        if (OUTBOUND.get() != null) {
            OUTBOUND.get().guard("sandbox").resetCircuitBreakers();
        }
    }

    static {
        try {
            DB = Files.createTempDirectory("stateless-shipping-api").resolve("shipping.db");
            normal();
            PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PROVIDER.setExecutor(THREADS);
            PROVIDER.createContext("/rest/rates", exchange -> {
                RATE_CALLS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                respond(exchange, RATE_STATUS.get(), "application/json", RATE_RESPONSE.get().getBytes(StandardCharsets.UTF_8));
            });
            PROVIDER.createContext("/rest/shipments", exchange -> {
                if (exchange.getRequestMethod().equals("POST")) {
                    CREATE_CALLS.incrementAndGet();
                    CREATE_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(exchange, CREATE_STATUS.get(), "application/json", CREATE_RESPONSE.get().getBytes(StandardCharsets.UTF_8));
                } else if (exchange.getRequestURI().getPath().endsWith("/label")) {
                    LABEL_CALLS.incrementAndGet();
                    respond(exchange, LABEL_STATUS.get(), LABEL_TYPE.get(), LABEL_BODY.get());
                } else {
                    respond(exchange, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                }
            });
            PROVIDER.createContext("/test-control", exchange -> {
                String query = String.valueOf(exchange.getRequestURI().getQuery());
                if (query.contains("mode=normal")) {
                    normal();
                } else if (query.contains("mode=rate-error")) {
                    RATE_STATUS.set(500);
                } else if (query.contains("mode=empty-rates")) {
                    RATE_STATUS.set(200);
                    RATE_RESPONSE.set("{\"availableRates\":[]}");
                } else if (query.contains("mode=create-reject")) {
                    CREATE_STATUS.set(400);
                } else if (query.contains("mode=create-uncertain")) {
                    CREATE_STATUS.set(500);
                } else if (query.contains("mode=manual-review")) {
                    JDBC.get().update("UPDATE shipment_bookings SET submission_deadline_at=0");
                    DISCOVERY.get().discover();
                } else if (query.contains("mode=label-pdf")) {
                    LABEL_STATUS.set(200);
                    LABEL_TYPE.set("application/pdf");
                    try (var fixture = ShippingApiTest.class.getResourceAsStream("/fixtures/shipping-label.pdf")) {
                        LABEL_BODY.set(java.util.Objects.requireNonNull(fixture).readAllBytes());
                    }
                } else if (query.contains("mode=label-url")) {
                    LABEL_STATUS.set(200);
                    LABEL_TYPE.set("application/json");
                    LABEL_BODY.set("{\"labelUrl\":\"https://labels.example.com/label.pdf\"}".getBytes(StandardCharsets.UTF_8));
                } else if (query.contains("mode=label-error")) {
                    LABEL_STATUS.set(500);
                } else if (query.contains("mode=reset-counts")) {
                    RATE_CALLS.set(0);
                    CREATE_CALLS.set(0);
                    LABEL_CALLS.set(0);
                }
                respond(exchange, 200, "application/json", ("{\"rateCalls\":" + RATE_CALLS.get()
                        + ",\"createCalls\":" + CREATE_CALLS.get() + ",\"labelCalls\":" + LABEL_CALLS.get() + "}")
                        .getBytes(StandardCharsets.UTF_8));
            });
            PROVIDER.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    static void respond(
            HttpExchange exchange,
            int status,
            String type,
            byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry properties) {
        properties.add("shipping.provider.base-url", () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
        properties.add("shipping.provider.username", () -> "stub-user");
        properties.add("shipping.provider.password", () -> "stub-password");
        properties.add("shipping.provider.label-hosts", () -> "labels.example.com");
        properties.add("shipping.provider.create-timeout", () -> "500ms");
        properties.add("shipping.database-path", DB::toString);
        properties.add("shipping.auth.token-secret", () -> "test-only-signing-secret-at-least-32-characters");
        properties.add("shipping.auth.users[0].username", () -> "alpha");
        properties.add("shipping.auth.users[0].password", () -> "test-alpha-password");
        properties.add("shipping.auth.users[0].user-id", () -> "guest-user");
        properties.add("shipping.auth.users[0].display-name", () -> "Alpha Operator");
        properties.add("shipping.auth.users[1].username", () -> "beta");
        properties.add("shipping.auth.users[1].password", () -> "test-beta-password");
        properties.add("shipping.auth.users[1].user-id", () -> "user-beta");
        properties.add("shipping.auth.users[1].display-name", () -> "Beta Operator");
        for (String setting : List.of("quotes-per-minute", "bookings-per-minute", "labels-per-minute", "login-per-second")) {
            properties.add("shipping.auth." + setting, () -> "10000");
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    DataSource source;
    @Autowired
    PendingShipmentDiscovery discovery;
    @Autowired
    OutboundClientRegistry outbound;
    @Autowired
    SessionTokenService tokens;

    @BeforeEach
    void reset() {
        OUTBOUND.set(outbound);
        DISCOVERY.set(discovery);
        JDBC.set(new JdbcTemplate(source));
        JDBC.get().update("DELETE FROM booking_reconciliation_tasks");
        JDBC.get().update("DELETE FROM shipment_bookings");
        normal();
        RATE_CALLS.set(0);
        CREATE_CALLS.set(0);
        LABEL_CALLS.set(0);
    }

    JsonNode rates() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON)
                .content(BookingFixtures.INPUT)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    String command(String quote) throws Exception {
        return mapper.writeValueAsString(BookingFixtures.command(quote));
    }

    JsonNode create(String quote, int expected) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                .content(command(quote))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    @Test
    void ratesExposeProviderIdentityAndNeverWriteBusinessRows() throws Exception {
        var quotes = rates().path("quotes");
        assertThat(quotes).hasSize(2);
        assertThat(quotes.get(0).path("providerId").asText()).isEqualTo("sandbox");
        assertThat(quotes.get(0).path("providerQuoteId").asText()).startsWith("provider-quote-");
        assertThat(quotes.get(0).has("quoteId")).isFalse();
        assertThat(quotes.get(0).path("total").path("amount").asText()).isEqualTo("27.35");
        rates();
        assertThat(RATE_CALLS).hasValue(2);
        mvc.perform(get("/api/quotes/old-request")).andExpect(status().isNotFound());
        assertNoBusinessRows();
        assertThat(JDBC.get().queryForList("SELECT name FROM sqlite_master WHERE type='table'", String.class))
                .doesNotContain("shipment_requests", "shipment_quotes");
    }

    void assertNoBusinessRows() {
        assertThat(JDBC.get().queryForObject("SELECT count(*) FROM shipment_bookings", Integer.class)).isZero();
        assertThat(JDBC.get().queryForObject("SELECT count(*) FROM booking_reconciliation_tasks", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500})
    void quoteErrorsAndEmptyResultsDoNotWrite(int providerStatus) throws Exception {
        RATE_STATUS.set(providerStatus);
        mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(BookingFixtures.INPUT))
                .andExpect(status().is(providerStatus == 404 ? 200 : providerStatus == 400 ? 400 : 502));
        assertNoBusinessRows();
    }

    @Test
    void duplicateCreateAndReadOnlyLookupUseOriginalSnapshotWithoutResending() throws Exception {
        var booking = create("Opaque/Case+Quote", 201);
        String id = booking.path("bookingId").asText();
        var snapshot = JDBC.get().queryForObject("SELECT submission_snapshot FROM shipment_bookings", String.class);
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerId\":\"sandbox\",\"providerQuoteId\":\"Opaque/Case+Quote\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingId").value(id));
        mvc.perform(get("/api/bookings/lookup").param("providerId", "sandbox").param("providerQuoteId", "Opaque/Case+Quote"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookingId").value(id));
        mvc.perform(get("/api/bookings/lookup").param("providerId", "sandbox").param("providerQuoteId", "absent"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/" + id + "/retry")).andExpect(status().isConflict());
        assertThat(CREATE_CALLS).hasValue(1);
        assertThat(JDBC.get().queryForObject("SELECT submission_snapshot FROM shipment_bookings", String.class)).isEqualTo(snapshot);
        assertThat(JDBC.get().queryForObject("SELECT provider_actual_total_minor FROM shipment_bookings", Long.class)).isNull();
        assertThat(mapper.readTree(CREATE_BODY.get()).path("quoteId").asText()).isEqualTo("Opaque/Case+Quote");
    }

    @Test
    void invalidSnapshotIsRejectedBeforeAnyProviderWrite() throws Exception {
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerId\":\"sandbox\",\"providerQuoteId\":\"new\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors").isNotEmpty());
        assertThat(CREATE_CALLS).hasValue(0);
        assertNoBusinessRows();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"shipId\":0}", "{\"shipId\":true}", "{\"shipId\":1.5}", "{\"shipId\":\"9223372036854775808\"}"})
    void malformedSuccessIsUnknownAndNeverResent(String body) throws Exception {
        CREATE_RESPONSE.set(body);
        var booking = create("invalid-receipt", 202);
        assertThat(booking.path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        create("invalid-receipt", 202);
        mvc.perform(post("/api/bookings/" + booking.path("bookingId").asText() + "/retry"))
                .andExpect(status().isConflict());
        assertThat(CREATE_CALLS).hasValue(1);
    }

    @Test
    void rejectionIsDurableAndDifferentQuotesRemainIndependent() throws Exception {
        CREATE_STATUS.set(400);
        assertThat(create("rejected", 422).path("status").asText()).isEqualTo("REJECTED");
        create("rejected", 200);
        CREATE_STATUS.set(201);
        create("different", 201);
        assertThat(CREATE_CALLS).hasValue(2);
    }

    @Test
    void accountOwnershipAppliesToStatusLookupRetryAndLabels() throws Exception {
        String id = create("same-quote", 201).path("bookingId").asText();
        String token = tokens.issueTokenForUser("user-beta", FP).token();
        for (String suffix : List.of("", "/label")) {
            mvc.perform(get("/api/bookings/" + id + suffix).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/bookings/lookup").param("providerId", "sandbox").param("providerQuoteId", "same-quote")
                .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/" + id + "/retry").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        assertThat(CREATE_CALLS).hasValue(1);
    }

    @Test
    void unknownBecomesManualReviewWhileReadsRemainReadOnly() throws Exception {
        CREATE_STATUS.set(500);
        var booking = create("unknown", 202);
        String id = booking.path("bookingId").asText();
        JDBC.get().update("UPDATE shipment_bookings SET submission_deadline_at=0");
        mvc.perform(get("/api/bookings/" + id)).andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"));
        assertThat(JDBC.get().queryForObject("SELECT count(*) FROM booking_reconciliation_tasks", Integer.class)).isZero();
        discovery.discover();
        mvc.perform(get("/api/bookings/" + id)).andExpect(jsonPath("$.status").value("MANUAL_REVIEW"));
        create("unknown", 200);
        assertThat(CREATE_CALLS).hasValue(1);
    }

    @Test
    void labelPendingPdfAndUrlNeverRecreateShipment() throws Exception {
        String id = create("label", 201).path("bookingId").asText();
        mvc.perform(get("/api/bookings/" + id + "/label")).andExpect(status().isAccepted());
        LABEL_STATUS.set(200);
        LABEL_TYPE.set("application/pdf");
        LABEL_BODY.set("%PDF-test".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/bookings/" + id + "/label")).andExpect(status().isOk());
        LABEL_TYPE.set("application/json");
        LABEL_BODY.set("{\"labelUrl\":\"https://labels.example.com/label.pdf\"}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/bookings/" + id + "/label").header("Accept", "application/json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.labelUrl").value("https://labels.example.com/label.pdf"));
        assertThat(CREATE_CALLS).hasValue(1);
    }

    @Test
    @Tag("browser")
    void endToEndBrowserJourneyCoversRatesValidationExpiryRejectionUncertaintyAndDelayedLabels() throws Exception {
        Path output = DB.getParent().resolve("browser-output.log");
        var builder = new ProcessBuilder("node", Path.of("tests/browser/shipping-journey.cjs").toAbsolutePath().toString(),
                "http://127.0.0.1:" + port + "/", "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/test-control");
        builder.environment().remove("NODE_PATH");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        var process = builder.start();
        try {
            assertThat(process.waitFor(150, TimeUnit.SECONDS)).as("Browser timeout: %s", output).isTrue();
            assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @AfterAll
    static void stop() {
        PROVIDER.stop(0);
        THREADS.shutdownNow();
    }
}
