package com.enterprise.shipping.workflow;

import com.enterprise.shipping.Application;
import com.enterprise.shipping.provider.sandbox.config.ProviderProperties;
import com.enterprise.shipping.quote.contract.QuoteSelectionPort;
import com.enterprise.shipping.reconciliation.application.PendingShipmentDiscovery;
import com.enterprise.shipping.shared.api.WorkflowException;
import com.enterprise.shipping.auth.application.GuestSessionTokenService;
import com.enterprise.shipping.shared.infrastructure.persistence.WorkflowMigrations;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

/** Real HTTP adapter and SQLite, without contacting a chargeable external service. */
@SpringBootTest(
        classes = {Application.class, WorkflowApiTest.TestAuthDefaults.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
@ActiveProfiles("workflow")
class WorkflowApiTest {
    static final String DEFAULT_TEST_FINGERPRINT = "fp-test-suite-default-01";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAuthDefaults {
        @Bean
        MockMvcBuilderCustomizer defaultMockMvcGuestAuth(GuestSessionTokenService tokens) {
            return builder -> {
                var issued = tokens.issueTokenForUser("demo-user", DEFAULT_TEST_FINGERPRINT);
                builder.defaultRequest(get("/")
                        .header("Authorization", "Bearer " + issued.token())
                        .header("X-Client-Fingerprint", DEFAULT_TEST_FINGERPRINT));
            };
        }
    }
    static final HttpServer PROVIDER;
    static final ExecutorService THREADS=Executors.newCachedThreadPool();
    static final AtomicReference<String> RATE_BODY=new AtomicReference<>();
    static final AtomicReference<String> RATE_AUTH=new AtomicReference<>();
    static final AtomicInteger RATE_CALLS=new AtomicInteger();
    static final AtomicInteger STATUS=new AtomicInteger(200);
    static final AtomicReference<String> RATE_RESPONSE=new AtomicReference<>();
    static final AtomicInteger CREATE_STATUS=new AtomicInteger(201);
    static final AtomicInteger CREATE_CALLS=new AtomicInteger();
    static final AtomicReference<String> CREATE_BODY=new AtomicReference<>();
    static final AtomicReference<String> CREATE_RESPONSE=new AtomicReference<>("{\"shipId\":123,\"trackingNumbers\":[\"TRACK123\"]}");
    static final AtomicBoolean DROP_CREATE_RESPONSE=new AtomicBoolean();
    static final AtomicReference<Runnable> BEFORE_CREATE_RESPONSE=new AtomicReference<>(()->{});
    static final AtomicInteger LOOKUP_CALLS=new AtomicInteger();
    static final AtomicReference<PendingShipmentDiscovery> TEST_DISCOVERY=new AtomicReference<>();
    static final AtomicInteger LABEL_STATUS=new AtomicInteger(202);
    static final AtomicInteger LABEL_CALLS=new AtomicInteger();
    static final AtomicReference<String> LABEL_CONTENT_TYPE=new AtomicReference<>("application/json");
    static final AtomicReference<byte[]> LABEL_BYTES=new AtomicReference<>("{}".getBytes(StandardCharsets.UTF_8));
    static final AtomicReference<JdbcTemplate> TEST_JDBC=new AtomicReference<>();
    static final AtomicReference<com.enterprise.shipping.provider.sandbox.SandboxProviderClient> TEST_ADAPTER=new AtomicReference<>();
    static final Path DB;
    static final String DEFAULT_RATES="""
        {"availableRates":[
          {"quoteId":"provider-quote-2","carrierId":"22","carrierName":"Loomis","serviceId":"2","serviceName":"Express","totalCharge":{"amount":3590,"currency":"CAD"},"transitDays":1,"transitUnit":"Days"},
          {"quoteId":"provider-quote","carrierId":"14","carrierName":"Carrier","serviceId":"1","serviceName":"Ground","totalCharge":{"amount":2735,"currency":"CAD"},"transitDays":2}
        ]}
        """;
    static {
        try {
            DB=Files.createTempDirectory("workflow-test").resolve("shipping.db");
            RATE_RESPONSE.set(DEFAULT_RATES);
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);PROVIDER.setExecutor(THREADS);
            PROVIDER.createContext("/rest/rates",e->{
                RATE_CALLS.incrementAndGet();
                RATE_AUTH.set(e.getRequestHeaders().getFirst("Authorization"));
                RATE_BODY.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                respond(e,STATUS.get(),"application/json",RATE_RESPONSE.get().getBytes(StandardCharsets.UTF_8));
            });
            PROVIDER.createContext("/rest/shipments",e->{
                String path=e.getRequestURI().getPath();
                if(path.endsWith("/label")&&e.getRequestMethod().equals("GET")) {
                    LABEL_CALLS.incrementAndGet();
                    respond(e,LABEL_STATUS.get(),LABEL_CONTENT_TYPE.get(),LABEL_BYTES.get());
                } else if(e.getRequestMethod().equals("POST")) {
                    CREATE_CALLS.incrementAndGet();
                    CREATE_BODY.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    BEFORE_CREATE_RESPONSE.get().run();
                    if(DROP_CREATE_RESPONSE.get()) { e.close(); return; }
                    respond(e,CREATE_STATUS.get(),"application/json",CREATE_RESPONSE.get().getBytes(StandardCharsets.UTF_8));
                } else {
                    LOOKUP_CALLS.incrementAndGet();
                    respond(e,202,"application/json","{}".getBytes(StandardCharsets.UTF_8));
                }
            });
            PROVIDER.createContext("/test-control",e->{
                String query=Objects.toString(e.getRequestURI().getQuery(),"");
                if(query.contains("mode=rate-error")) STATUS.set(500);
                else if(query.contains("mode=empty-rates")) { STATUS.set(200); RATE_RESPONSE.set("{\"availableRates\":[]}"); }
                else if(query.contains("mode=normal")) {
                    if(TEST_ADAPTER.get()!=null) TEST_ADAPTER.get().resetCircuitBreakers();
                    STATUS.set(200); RATE_RESPONSE.set(DEFAULT_RATES);
                    CREATE_STATUS.set(201); CREATE_RESPONSE.set("{\"shipId\":98765,\"trackingNumbers\":[\"TRK-98765\"]}");
                    LABEL_STATUS.set(202); LABEL_CONTENT_TYPE.set("application/json"); LABEL_BYTES.set("{}".getBytes(StandardCharsets.UTF_8));
                } else if(query.contains("mode=expire-quotes")) {
                    TEST_JDBC.get().update("UPDATE shipment_quotes SET expires_at=0");
                } else if(query.contains("mode=create-reject")) {
                    CREATE_STATUS.set(422); CREATE_RESPONSE.set("{\"error\":\"rejected\"}");
                } else if(query.contains("mode=create-uncertain")) {
                    CREATE_STATUS.set(500); CREATE_RESPONSE.set("{\"error\":\"unknown\"}");
                } else if(query.contains("mode=label-pdf")) {
                    LABEL_STATUS.set(200); LABEL_CONTENT_TYPE.set("application/pdf");
                    try (var fixture = WorkflowApiTest.class.getResourceAsStream("/fixtures/shipping-label.pdf")) {
                        LABEL_BYTES.set(java.util.Objects.requireNonNull(fixture).readAllBytes());
                    }
                } else if(query.contains("mode=manual-review")) {
                    TEST_JDBC.get().update("UPDATE shipment_bookings SET submission_deadline_at=0");
                    TEST_DISCOVERY.get().discover();
                } else if(query.contains("mode=label-error")) {
                    LABEL_STATUS.set(500); LABEL_CONTENT_TYPE.set("application/json");
                    LABEL_BYTES.set("{}".getBytes(StandardCharsets.UTF_8));
                } else if(query.contains("mode=label-url")) {
                    LABEL_STATUS.set(200); LABEL_CONTENT_TYPE.set("application/json");
                    LABEL_BYTES.set("{\"labelUrl\":\"https://labels.example.com/label.pdf\"}".getBytes(StandardCharsets.UTF_8));
                } else if(query.contains("mode=reset-counts")) {
                    RATE_CALLS.set(0); CREATE_CALLS.set(0); LABEL_CALLS.set(0);
                }
                String stats=String.format("{\"rateCalls\":%d,\"createCalls\":%d,\"labelCalls\":%d}",
                        RATE_CALLS.get(),CREATE_CALLS.get(),LABEL_CALLS.get());
                respond(e,200,"application/json",stats.getBytes(StandardCharsets.UTF_8));
            });
            PROVIDER.start();
        } catch(Exception e) {throw new ExceptionInInitializerError(e);}
    }
    static void respond(HttpExchange exchange,int status,String contentType,byte[] bytes) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type",contentType);
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("shipping.provider.base-url",()->"http://127.0.0.1:"+PROVIDER.getAddress().getPort());
        r.add("shipping.provider.username",()->"stub-user");r.add("shipping.provider.password",()->"stub-password");
        r.add("shipping.provider.label-hosts",()->"labels.example.com");
        r.add("shipping.database-path",DB::toString);
        r.add("shipping.provider.create-timeout",()->"500ms");
    }
    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource source;
    @Autowired QuoteSelectionPort selections;
    @Autowired PendingShipmentDiscovery discovery;
    @Autowired ProviderProperties providerProperties;
    @Autowired com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort recoveryCapabilities;
    @Autowired MeterRegistry meterRegistry;
    @Autowired com.enterprise.shipping.provider.sandbox.SandboxProviderClient adapter;
    static final String ADDRESS="""
        {"contactName":"Test User","addressLine1":"100 Test Street","city":"Toronto","province":"ON",
         "postalCode":"M5V 3L9","countryCode":"CA","phone":"4165550100"}
        """;
    static final String INPUT="{\"sender\":"+ADDRESS+",\"recipient\":"+ADDRESS+",\"parcel\":{\"length\":12,\"width\":10,\"height\":8,\"weight\":5}}";
    @BeforeEach void reset() {
        adapter.resetCircuitBreakers();
        TEST_ADAPTER.set(adapter);
        TEST_DISCOVERY.set(discovery);
        LOOKUP_CALLS.set(0);
        var jdbc=new JdbcTemplate(source);
        TEST_JDBC.set(jdbc);
        jdbc.update("DELETE FROM booking_reconciliation_tasks");
        jdbc.update("DELETE FROM shipment_bookings");
        jdbc.update("DELETE FROM shipment_quotes");
        jdbc.update("DELETE FROM shipment_requests");
        STATUS.set(200);RATE_CALLS.set(0);RATE_RESPONSE.set(DEFAULT_RATES);
        CREATE_STATUS.set(201);CREATE_CALLS.set(0);
        CREATE_RESPONSE.set("{\"shipId\":123,\"trackingNumbers\":[\"TRACK123\"]}");
        DROP_CREATE_RESPONSE.set(false);
        BEFORE_CREATE_RESPONSE.set(()->{});
        LABEL_STATUS.set(202);LABEL_CALLS.set(0);LABEL_CONTENT_TYPE.set("application/json");
        LABEL_BYTES.set("{}".getBytes(StandardCharsets.UTF_8));
    }
    JsonNode rates() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/quotes")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    @Test void persistsExactOffersAndDoesNotExposeProviderQuoteToken() throws Exception {
        var response=rates();
        assertThat(response.path("state").asText()).isEqualTo("READY");
        assertThat(response.path("quotes")).hasSize(2);
        var quote=response.path("quotes").get(0);
        assertThat(quote.path("total").path("amount").asText()).isEqualTo("27.35");
        assertThat(quote.path("transitDays").asInt()).isEqualTo(2);
        assertThat(quote.has("providerQuoteId")).isFalse();
        assertThat(selections.resolve("demo-user",quote.path("quoteId").asText()).providerQuoteId()).isEqualTo("provider-quote");
        var request=mapper.readTree(RATE_BODY.get());
        assertThat(request.path("unitOfMeasurement").asText()).isEqualTo("IMPERIAL");
        assertThat(request.path("from").path("attention").asText()).isEqualTo("Test User");
        assertThat(request.path("lineItems").get(0).path("weight").asText()).isEqualTo("5");
        assertThat(RATE_AUTH.get()).startsWith("Basic ");
        assertThat(new JdbcTemplate(source).queryForObject("SELECT count(*) FROM shipment_requests",Integer.class)).isEqualTo(1);
    }
    @Test void supportsAllCanadianProvincesMetricAndMixedUnitsAndValidatesDates() throws Exception {
        for(String province:List.of("BC","AB","SK","MB","ON","QC","NB","NS","PE","NL","YT","NT","NU")) {
            String payload=INPUT.replace("\"province\":\"ON\"","\"province\":\""+province+"\"")
                    .replace("\"weight\":5","\"weight\":2.5,\"dimensionUnit\":\"CM\",\"weightUnit\":\"KG\"");
            mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isCreated());
            var sent=mapper.readTree(RATE_BODY.get());
            assertThat(sent.path("unitOfMeasurement").asText()).isEqualTo("METRIC");
            assertThat(sent.path("lineItems").get(0).path("weight").asText()).isEqualTo("2.5");
        }
        String mixed=INPUT.replace("\"weight\":5","\"weight\":10,\"dimensionUnit\":\"CM\",\"weightUnit\":\"LB\"");
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(mixed)).andExpect(status().isCreated());
        assertThat(mapper.readTree(RATE_BODY.get()).path("lineItems").get(0).path("weight").asText()).isEqualTo("4.5359237");

        String pastDate="{\"sender\":"+ADDRESS+",\"recipient\":"+ADDRESS+",\"parcel\":{\"length\":12,\"width\":10,\"height\":8,\"weight\":5},\"shippingDate\":\"2020-01-01\"}";
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(pastDate))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SHIPPING_DATE"));
    }
    @Test void invalidFieldsNeverReachProvider() throws Exception {
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(INPUT.replace("\"weight\":5","\"weight\":0")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("parcel.weight"));
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(INPUT.replace("\"weight\":5","\"weight\":5.12345")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("parcel.weight"));
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(INPUT.replace("Test User", "A".repeat(129))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("sender.contactName")));
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(INPUT.replace("M5V 3L9","12345")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("sender.postalCode")));
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{malformed"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(RATE_CALLS).hasValue(0);
    }
    @Test void emptyRatesReturnsNoRatesState() throws Exception {
        RATE_RESPONSE.set("{\"availableRates\":[]}");
        var response=rates();
        assertThat(response.path("state").asText()).isEqualTo("NO_RATES");
        assertThat(response.path("quotes")).isEmpty();
        assertThat(new JdbcTemplate(source).queryForObject("SELECT state FROM shipment_requests",String.class)).isEqualTo("NO_RATES");
    }
    @Test void rejectsOtherUsersExpiredAndUnbookableSelection() throws Exception {
        String id=rates().path("quotes").get(0).path("quoteId").asText();
        assertThatThrownBy(()->selections.resolve("other-user",id)).isInstanceOf(WorkflowException.class).hasMessage("RESOURCE_NOT_FOUND");
        var jdbc=new JdbcTemplate(source);
        jdbc.update("UPDATE shipment_quotes SET provider_quote_id='' WHERE quote_id=?",id);
        mvc.perform(post("/api/bookings").header("Idempotency-Key","key-unbookable").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quoteId\":\""+id+"\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_NOT_BOOKABLE"));
        jdbc.update("UPDATE shipment_quotes SET provider_quote_id='provider-quote', expires_at=0 WHERE quote_id=?",id);
        mvc.perform(post("/api/bookings").header("Idempotency-Key","key-expired").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quoteId\":\""+id+"\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
        assertThat(CREATE_CALLS).hasValue(0);
    }
    @Test void providerErrorsLeaveDurableFailedRequest() throws Exception {
        STATUS.set(500);
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        assertThat(new JdbcTemplate(source).queryForObject("SELECT state FROM shipment_requests",String.class)).isEqualTo("RATE_FAILED");

        STATUS.set(401);
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("PROVIDER_AUTHENTICATION_FAILED"));

        STATUS.set(200);
        RATE_RESPONSE.set("{\"availableRates\":[{\"quoteId\":\"q1\",\"carrierId\":\"1\",\"serviceId\":\"1\",\"totalCharge\":{\"amount\":100,\"currency\":\"USD\"}}]}");
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("PROVIDER_INVALID_RESPONSE"));
    }
    @Test void createsConfirmedBookingAndReplaysIdempotentlyWithoutDuplicateSubmission() throws Exception {
        String quoteId=rates().path("quotes").get(0).path("quoteId").asText();
        var created=mvc.perform(post("/api/bookings").header("Idempotency-Key","idem-001")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.shipmentId").value("123"))
                .andExpect(jsonPath("$.trackingNumber").value("TRACK123"))
                .andExpect(jsonPath("$.labelStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String bookingId=mapper.readTree(created).path("bookingId").asText();
        assertThat(mapper.readTree(CREATE_BODY.get())).isEqualTo(mapper.readTree("{\"quoteId\":\"provider-quote\"}"));
        assertThat(CREATE_CALLS).hasValue(1);

        mvc.perform(post("/api/bookings").header("Idempotency-Key","idem-001")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(CREATE_CALLS).hasValue(1);

        mvc.perform(get("/api/bookings/"+bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(new JdbcTemplate(source).queryForObject("SELECT count(*) FROM booking_reconciliation_tasks",Integer.class)).isZero();
    }
    @Test void concurrentSameKeyAndDifferentKeySameRequestRacesSubmitAtMostOnce() throws Exception {
        var quotes=rates().path("quotes");
        String quote1=quotes.get(0).path("quoteId").asText();
        String quote2=quotes.get(1).path("quoteId").asText();

        int threads=8;
        var pool=Executors.newFixedThreadPool(threads);
        try {
            var startSameKey=new CountDownLatch(1);
            List<Future<Integer>> sameKeyFutures=new ArrayList<>();
            for(int i=0;i<threads;i++) {
                sameKeyFutures.add(pool.submit(()->{
                    startSameKey.await();
                    return mvc.perform(post("/api/bookings").header("Idempotency-Key","race-same-key")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quote1+"\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            startSameKey.countDown();
            for(var f:sameKeyFutures) assertThat(f.get()).isIn(200,201,202);
            assertThat(CREATE_CALLS).hasValue(1);

            var secondQuotes=rates().path("quotes");
            String req2Quote1=secondQuotes.get(0).path("quoteId").asText();
            String req2Quote2=secondQuotes.get(1).path("quoteId").asText();
            CREATE_CALLS.set(0);
            var startDiffKey=new CountDownLatch(1);
            List<Future<org.springframework.mock.web.MockHttpServletResponse>> diffKeyFutures=new ArrayList<>();
            for(int i=0;i<threads;i++) {
                final int idx=i;
                diffKeyFutures.add(pool.submit(()->{
                    startDiffKey.await();
                    String chosen=idx%2==0?req2Quote1:req2Quote2;
                    return mvc.perform(post("/api/bookings").header("Idempotency-Key","race-diff-key-"+idx)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+chosen+"\"}"))
                            .andReturn().getResponse();
                }));
            }
            startDiffKey.countDown();
            int winners=0,conflicts=0;
            String winnerBookingId=null;
            List<String> conflictBookingIds=new ArrayList<>();
            for(var f:diffKeyFutures) {
                var resp=f.get();
                var body=mapper.readTree(resp.getContentAsString());
                if(resp.getStatus()==201) {
                    winners++;
                    winnerBookingId=body.path("bookingId").asText();
                } else if(resp.getStatus()==409) {
                    conflicts++;
                    assertThat(body.path("code").asText()).isEqualTo("SHIPMENT_ALREADY_SUBMITTED");
                    conflictBookingIds.add(body.path("booking").path("bookingId").asText());
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(conflicts).isEqualTo(threads-1);
            assertThat(conflictBookingIds).containsOnly(winnerBookingId);
            assertThat(CREATE_CALLS).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }
    @Test void rejectsMissingInvalidOrConflictingIdempotencyKeys() throws Exception {
        var quotes=rates().path("quotes");
        String quote1=quotes.get(0).path("quoteId").asText();
        String quote2=quotes.get(1).path("quoteId").asText();

        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quote1+"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
        mvc.perform(post("/api/bookings").header("Idempotency-Key","bad key with spaces")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quote1+"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        mvc.perform(post("/api/bookings").header("Idempotency-Key","conflict-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quote1+"\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/bookings").header("Idempotency-Key","conflict-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quote2+"\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(CREATE_CALLS).hasValue(1);
    }
    @Test void definiteProviderRejectionReturns422AndReplaysAs200Rejected() throws Exception {
        String quoteId=rates().path("quotes").get(0).path("quoteId").asText();
        CREATE_STATUS.set(422);
        var rejected=mvc.perform(post("/api/bookings").header("Idempotency-Key","reject-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCode").value("CARRIER_REJECTED"))
                .andReturn().getResponse().getContentAsString();
        String bookingId=mapper.readTree(rejected).path("bookingId").asText();

        mvc.perform(post("/api/bookings").header("Idempotency-Key","reject-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(CREATE_CALLS).hasValue(1);
    }
    @Test void ambiguousProviderOutcomeStaysPendingConfirmationAndRegistersPausedManualReviewTask() throws Exception {
        String quoteId=rates().path("quotes").get(0).path("quoteId").asText();
        CREATE_STATUS.set(500);
        var pending=mvc.perform(post("/api/bookings").header("Idempotency-Key","uncertain-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After","2"))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.errorCode").value("CARRIER_OUTCOME_UNKNOWN"))
                .andReturn().getResponse().getContentAsString();
        String bookingId=mapper.readTree(pending).path("bookingId").asText();

        mvc.perform(post("/api/bookings").header("Idempotency-Key","uncertain-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.bookingId").value(bookingId))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"));
        assertThat(CREATE_CALLS).hasValue(1);

        var jdbc=new JdbcTemplate(source);
        jdbc.update("UPDATE shipment_bookings SET submission_deadline_at=0 WHERE booking_id=?",bookingId);
        discovery.discover();
        discovery.discover();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_reconciliation_tasks WHERE booking_id=?",Integer.class,bookingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM booking_reconciliation_tasks WHERE booking_id=?",String.class,bookingId)).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM booking_reconciliation_tasks WHERE booking_id=?",String.class,bookingId))
                .isEqualTo("AUTHORITATIVE_LOOKUP_UNSUPPORTED");
        mvc.perform(get("/api/bookings/"+bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.errorCode").value("AUTHORITATIVE_LOOKUP_UNSUPPORTED"));
        mvc.perform(post("/api/bookings").header("Idempotency-Key","uncertain-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.status").value("MANUAL_REVIEW"));
        mvc.perform(post("/api/bookings").header("Idempotency-Key","competing-manual-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.booking.status").value("MANUAL_REVIEW"));
        assertThat(CREATE_CALLS).hasValue(1);
        assertThat(LOOKUP_CALLS).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipment_bookings WHERE status IN ('PENDING','PENDING_INQUIRY')",Integer.class))
                .isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
            "true", "false", "null", "0", "-1", "1.5", "[]", "{}",
            "\"true\"", "\"0\"", "\"abc\"", "9223372036854775808"})
    void malformedSuccessStaysUncertainAndNeverResubmits(String invalidId) throws Exception {
        String quoteId=rates().path("quotes").get(0).path("quoteId").asText();
        CREATE_RESPONSE.set("{\"shipId\":"+invalidId+"}");
        String bookingId=submitPending(quoteId,"malformed-key");
        assertUncertainReplay(quoteId,"malformed-key",bookingId);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"123", "\"123\"", "9223372036854775807"})
    void validNumericShipmentIdentityCanBeConfirmed(String validId) throws Exception {
        String quoteId=rates().path("quotes").get(0).path("quoteId").asText();
        CREATE_RESPONSE.set("{\"shipId\":"+validId+"}");
        mvc.perform(post("/api/bookings").header("Idempotency-Key","valid-id-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(CREATE_CALLS).hasValue(1);
    }

    @Test void acceptedShipmentWithLostResponseDoesNotResubmit() throws Exception {
        String quoteId=rates().path("quotes").get(0).path("quoteId").asText();
        // The stub consumes and counts acceptance before closing without HTTP headers.
        DROP_CREATE_RESPONSE.set(true);
        String bookingId=submitPending(quoteId,"lost-response-key");
        assertUncertainReplay(quoteId,"lost-response-key",bookingId);
    }

    @Test void acceptedShipmentWithActualTransportTimeoutDoesNotResubmit() throws Exception {
        String quoteId=rates().path("quotes").get(0).path("quoteId").asText();
        CountDownLatch releaseResponse=new CountDownLatch(1);
        CountDownLatch responseFinished=new CountDownLatch(1);
        BEFORE_CREATE_RESPONSE.set(()->{
            try { releaseResponse.await(10,TimeUnit.SECONDS); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { responseFinished.countDown(); }
        });
        try {
            String bookingId=submitPending(quoteId,"timeout-key");
            assertThat(releaseResponse.getCount()).isEqualTo(1); // No provider response released.
            assertUncertainReplay(quoteId,"timeout-key",bookingId);
        } finally {
            releaseResponse.countDown();
            assertThat(responseFinished.await(5,TimeUnit.SECONDS)).isTrue();
        }
    }

    private String submitPending(String quoteId,String key) throws Exception {
        String response=mvc.perform(post("/api/bookings").header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.shipmentId").isEmpty())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("bookingId").asText();
    }

    private void assertUncertainReplay(String quoteId,String key,String bookingId) throws Exception {
        mvc.perform(post("/api/bookings").header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.bookingId").value(bookingId));
        mvc.perform(get("/api/bookings/"+bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"));
        assertThat(CREATE_CALLS).hasValue(1);
        var jdbc=new JdbcTemplate(source);
        jdbc.update("UPDATE shipment_bookings SET submission_deadline_at=0 WHERE booking_id=?",bookingId);
        discovery.discover();
        assertThat(jdbc.queryForObject("SELECT state FROM booking_reconciliation_tasks WHERE booking_id=?",String.class,bookingId))
                .isEqualTo("PAUSED");
        assertThat(CREATE_CALLS).hasValue(1);
    }

    @Test void lateConfirmationConvergesAfterDiscoveryRequiredManualReview() throws Exception {
        String quoteId=rates().path("quotes").get(0).path("quoteId").asText();
        var jdbc=new JdbcTemplate(source);
        BEFORE_CREATE_RESPONSE.set(()->{
            jdbc.update("UPDATE shipment_bookings SET submission_deadline_at=0");
            discovery.discover();
            assertThat(jdbc.queryForObject("SELECT status FROM shipment_bookings",String.class)).isEqualTo("MANUAL_REVIEW");
        });
        mvc.perform(post("/api/bookings").header("Idempotency-Key","late-confirm-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.shipmentId").value("123"));
    }
    @Test void providerCapabilitiesAreLocalAndScopedToThePersistedAccount() {
        var capability=recoveryCapabilities.capabilities(providerProperties.providerId(),providerProperties.accountAlias());
        assertThat(capability.authoritativeLookup()).isEqualTo(
                com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort.Support.UNSUPPORTED);
        assertThat(capability.creationIdempotency()).isEqualTo(
                com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort.Support.UNVERIFIED);
        assertThat(recoveryCapabilities.capabilities("another-provider","another-account").authoritativeLookup()).isEqualTo(
                com.enterprise.shipping.reconciliation.port.RecoveryCapabilityPort.Support.UNVERIFIED);
        assertThat(CREATE_CALLS).hasValue(0);
        assertThat(LOOKUP_CALLS).hasValue(0);
        assertThat(RATE_CALLS).hasValue(0);
    }

    @Test void labelRetrievalSupportsPendingPdfAndAllowlistedRedirectWithoutRecreatingShipment() throws Exception {
        String quoteId=rates().path("quotes").get(0).path("quoteId").asText();
        var created=mvc.perform(post("/api/bookings").header("Idempotency-Key","label-flow-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String bookingId=mapper.readTree(created).path("bookingId").asText();

        LABEL_STATUS.set(202);
        mvc.perform(get("/api/bookings/"+bookingId+"/label").header("Accept","application/json, application/pdf"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After","3"))
                .andExpect(jsonPath("$.labelStatus").value("PENDING"));

        byte[] pdfBytes="%PDF-1.4 sample label".getBytes(StandardCharsets.UTF_8);
        LABEL_STATUS.set(200);
        LABEL_CONTENT_TYPE.set("application/pdf");
        LABEL_BYTES.set(pdfBytes);
        mvc.perform(get("/api/bookings/"+bookingId+"/label").header("Accept","application/json, application/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(pdfBytes));
        mvc.perform(get("/api/bookings/"+bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labelStatus").value("AVAILABLE"));

        LABEL_CONTENT_TYPE.set("application/json");
        LABEL_BYTES.set("{\"labelUrl\":\"https://evil.example.com/label.pdf\"}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/bookings/"+bookingId+"/label").header("Accept","application/json"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROVIDER_INVALID_RESPONSE"));

        LABEL_BYTES.set("{\"labelUrl\":\"https://labels.example.com/label-123.pdf\"}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/bookings/"+bookingId+"/label").header("Accept","application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labelUrl").value("https://labels.example.com/label-123.pdf"));
        mvc.perform(get("/api/bookings/"+bookingId+"/label").header("Accept","text/html"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location","https://labels.example.com/label-123.pdf"));
        assertThat(CREATE_CALLS).hasValue(1);
    }
    @Test void enforcesSameOriginOnWritesAndExposesSafeMetricsAndIndexedQueryPlans() throws Exception {
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).header("Origin","https://evil.example.com")
                .contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));

        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).header("Origin","http://localhost")
                .contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().string("Cache-Control","no-store"));

        assertThat(providerProperties.toString()).contains("credentials=REDACTED").doesNotContain("stub-password");
        assertThat(meterRegistry.find("shipping.workflow.quote").timers()).isNotEmpty();
        assertThat(meterRegistry.find("shipping.carrier.transport.latency").timers()).isNotEmpty();

        var jdbc=new JdbcTemplate(source);
        var plan=String.join(" ",jdbc.query("""
                EXPLAIN QUERY PLAN SELECT booking_id, status FROM shipment_bookings
                WHERE status IN ('PENDING','PENDING_INQUIRY')
                  AND submission_deadline_at <= ?
                  AND (submission_deadline_at > ? OR (submission_deadline_at = ? AND booking_id > ?))
                ORDER BY submission_deadline_at, booking_id LIMIT 10
                """, (rs,n)->rs.getString("detail"), 1000L, -1L, -1L, ""));
        assertThat(plan).contains("idx_workflow_unresolved");

        String originalChecksum=jdbc.queryForObject("SELECT checksum FROM schema_migrations WHERE version='V001__quotes.sql'",String.class);
        jdbc.update("UPDATE schema_migrations SET checksum='tampered' WHERE version='V001__quotes.sql'");
        try {
            assertThatThrownBy(()->WorkflowMigrations.migrate(source,"V001__quotes.sql"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Applied migration checksum changed");
        } finally {
            jdbc.update("UPDATE schema_migrations SET checksum=? WHERE version='V001__quotes.sql'",originalChecksum);
            WorkflowMigrations.migrate(source,"V001__quotes.sql","V002__bookings.sql","V003__rate_request_idempotency.sql","V004__portable_schema_baseline.sql");
        }
    }
    @Test void rateCircuitBreakerOpeningDoesNotBlockBookingCreationCapacity() throws Exception {
        String quoteId=rates().path("quotes").get(0).path("quoteId").asText();
        STATUS.set(500);
        for(int i=0;i<4;i++) {
            mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(INPUT))
                    .andExpect(status().isBadGateway());
        }
        int callsBeforeShortCircuit=RATE_CALLS.get();
        mvc.perform(post("/api/quotes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_CAPACITY_EXCEEDED"));
        assertThat(RATE_CALLS).hasValue(callsBeforeShortCircuit);

        mvc.perform(post("/api/bookings").header("Idempotency-Key","isolated-create-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"quoteId\":\""+quoteId+"\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void rateEndpointEnforcesIdempotencyReplayConflictFailureStatusAndRetry() throws Exception {
        // 1. Missing or invalid Idempotency-Key on POST /api/quotes is rejected with 400
        mvc.perform(post("/api/quotes").contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
        mvc.perform(post("/api/quotes")
                        .header("Idempotency-Key", "invalid key with spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        assertThat(RATE_CALLS).hasValue(0);

        // 2. First request returns 201 Created; same-key replay returns 200 OK without calling provider
        var createdBody = mvc.perform(post("/api/quotes")
                        .header("Idempotency-Key", "rate-idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.state").value("READY"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String requestId = mapper.readTree(createdBody).path("requestId").asText();
        assertThat(RATE_CALLS).hasValue(1);

        mvc.perform(post("/api/quotes")
                        .header("Idempotency-Key", "rate-idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.state").value("READY"));
        assertThat(RATE_CALLS).hasValue(1);

        mvc.perform(get("/api/quotes/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.quotes").isArray());
        assertThat(RATE_CALLS).hasValue(1);

        // 3. Reusing the same key with changed parcel weight returns 409 IDEMPOTENCY_CONFLICT + operation location
        mvc.perform(post("/api/quotes")
                        .header("Idempotency-Key", "rate-idem-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT.replace("\"weight\":5", "\"weight\":9")))
                .andExpect(status().isConflict())
                .andExpect(header().string("Location", "/api/quotes/" + requestId))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.operation.id").value(requestId))
                .andExpect(jsonPath("$.operation.statusUrl").value("/api/quotes/" + requestId));
        assertThat(RATE_CALLS).hasValue(1);

        // 4. Failed rate execution returns 502 with operation metadata, GET shows RATE_FAILED, and same-key retry succeeds
        STATUS.set(500);
        var failedBody = mvc.perform(post("/api/quotes")
                        .header("Idempotency-Key", "rate-retry-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.operation.id").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String failedRequestId = mapper.readTree(failedBody).path("operation").path("id").asText();
        assertThat(RATE_CALLS).hasValue(2);

        mvc.perform(get("/api/quotes/" + failedRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(failedRequestId))
                .andExpect(jsonPath("$.state").value("RATE_FAILED"))
                .andExpect(jsonPath("$.quotes").isEmpty());
        assertThat(RATE_CALLS).hasValue(2);

        STATUS.set(200);
        mvc.perform(post("/api/quotes")
                        .header("Idempotency-Key", "rate-retry-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(failedRequestId))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.quotes").isNotEmpty());
        assertThat(RATE_CALLS).hasValue(3);
    }

    @Test
    void enforcesGuestTokenAndClientFingerprintAndIsolatesGuestSessions() throws Exception {
        // 1. Missing or invalid Authorization header is rejected with 401 UNAUTHORIZED
        mvc.perform(post("/api/quotes")
                        .header("Authorization", "")
                        .header("X-Client-Fingerprint", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(RATE_CALLS).hasValue(0);

        // 2. Issue a 2-hour Guest Token for Guest Alpha bound to fingerprint fp-alpha
        String fpAlpha = "fp-browser-alpha-00000001";
        var alphaAuthBody = mvc.perform(post("/api/auth/guest")
                        .header("Authorization", "")
                        .header("X-Client-Fingerprint", fpAlpha)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"client-alpha-12345678\",\"fingerprint\":\"" + fpAlpha + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("GUEST"))
                .andExpect(jsonPath("$.ttlSeconds").value(7200))
                .andExpect(header().exists("Set-Cookie"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode alphaAuth = mapper.readTree(alphaAuthBody);
        String alphaToken = alphaAuth.path("token").asText();
        String alphaUserId = alphaAuth.path("userId").asText();
        assertThat(alphaUserId).startsWith("guest-");

        // Re-requesting with the same clientId resolves to the same deterministic guest userId
        var alphaRepeatBody = mvc.perform(post("/api/auth/guest")
                        .header("Authorization", "")
                        .header("X-Client-Fingerprint", fpAlpha)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"client-alpha-12345678\",\"fingerprint\":\"" + fpAlpha + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(mapper.readTree(alphaRepeatBody).path("userId").asText()).isEqualTo(alphaUserId);

        // 3. Guest Alpha can obtain quotes when presenting matching token + fingerprint
        var alphaQuotesResp = mvc.perform(post("/api/quotes")
                        .header("Authorization", "Bearer " + alphaToken)
                        .header("X-Client-Fingerprint", fpAlpha)
                        .header("Idempotency-Key", "alpha-rate-key-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INPUT))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String alphaQuoteId = mapper.readTree(alphaQuotesResp).path("quotes").get(0).path("quoteId").asText();

        // 4. Presenting Guest Alpha's token with a different fingerprint is rejected with 401 UNAUTHORIZED
        mvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + alphaToken)
                        .header("X-Client-Fingerprint", "fp-different-device-999999")
                        .header("Idempotency-Key", "stolen-token-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":\"" + alphaQuoteId + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(CREATE_CALLS).hasValue(0);

        // 5. Guest Beta gets a distinct guest identity and cannot book Guest Alpha's quote (404 RESOURCE_NOT_FOUND)
        String fpBeta = "fp-browser-beta-00000002";
        var betaAuthBody = mvc.perform(post("/api/auth/guest")
                        .header("Authorization", "")
                        .header("X-Client-Fingerprint", fpBeta)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"client-beta-87654321\",\"fingerprint\":\"" + fpBeta + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode betaAuth = mapper.readTree(betaAuthBody);
        String betaToken = betaAuth.path("token").asText();
        String betaUserId = betaAuth.path("userId").asText();
        assertThat(betaUserId).isNotEqualTo(alphaUserId);

        mvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + betaToken)
                        .header("X-Client-Fingerprint", fpBeta)
                        .header("Idempotency-Key", "cross-guest-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":\"" + alphaQuoteId + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(CREATE_CALLS).hasValue(0);

        // 6. Expired token (past 2h TTL) is rejected
        Clock baseClock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
        var verifierAtIssue = new GuestSessionTokenService("unit-test-secret", Duration.ofHours(2), baseClock);
        var issued2h = verifierAtIssue.issueGuestToken("client-ttl-check-01", fpAlpha);
        var verifierBeforeExpiry = new GuestSessionTokenService(
                "unit-test-secret", Duration.ofHours(2), Clock.offset(baseClock, Duration.ofMinutes(119)));
        var verifierAfterExpiry = new GuestSessionTokenService(
                "unit-test-secret", Duration.ofHours(2), Clock.offset(baseClock, Duration.ofMinutes(121)));
        assertThat(verifierBeforeExpiry.verifyAndResolveUser(issued2h.token(), fpAlpha)).contains(issued2h.userId());
        assertThat(verifierAfterExpiry.verifyAndResolveUser(issued2h.token(), fpAlpha)).isEmpty();
    }
    @Test @Tag("browser")
    void endToEndBrowserJourneyCoversRatesValidationExpiryRejectionUncertaintyAndDelayedLabels() throws Exception {
        Path script=Paths.get("tests/browser/workflow-journey.cjs").toAbsolutePath();
        Path output=DB.getParent().resolve("browser-output.log");
        ProcessBuilder builder=new ProcessBuilder("node",script.toString(),
                "http://127.0.0.1:"+port+"/",
                "http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/test-control");
        // Resolve the pinned repository dependency; never depend on a developer's npm cache.
        builder.environment().remove("NODE_PATH");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process=builder.start();
        try {
            boolean finished=process.waitFor(90,TimeUnit.SECONDS);
            assertThat(finished).as("Browser timeout; output: %s",output).isTrue();
            assertThat(process.exitValue()).as(Files.readString(output)).isZero();
        } finally {
            if(process.isAlive()) process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);
        }
    }
    @AfterAll static void stop() {PROVIDER.stop(0);THREADS.shutdownNow();}
}
