package com.enterprise.shipping.booking;

import com.enterprise.shipping.Application;
import com.enterprise.shipping.booking.application.*;
import com.enterprise.shipping.demo.booking.SqliteDemoCarrier;
import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.booking.port.BookingStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"management.server.port=0", "shipping.booking.reconciliation-enabled=false"})
@ActiveProfiles("demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BookingApiTest {
    static final int PORT;
    static final Path DIRECTORY;
    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            PORT = socket.getLocalPort();
            DIRECTORY = Files.createTempDirectory("booking-api-test-");
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("shipping.booking.enabled-carriers[0]", () -> "fedex");
        registry.add("shipping.booking.enabled-carriers[1]", () -> "ups");
        registry.add("shipping.booking.enabled-carriers[2]", () -> " Purolator ");
        registry.add("shipping.booking.database-path", () -> DIRECTORY.resolve("app.db").toString());
        registry.add("demo.booking-carrier.database-path", () -> DIRECTORY.resolve("carrier.db").toString());
        registry.add("demo.booking-carrier.base-url", () -> "http://127.0.0.1:" + PORT + "/api/mock/bookings");
    }
    @Autowired TestRestTemplate client;
    @Autowired ObjectMapper mapper;
    @Autowired BookingReconciliationWorker worker;
    @Autowired BookingService service;
    @Autowired com.enterprise.shipping.booking.port.BookingOwnerProvider owners;
    @Autowired com.enterprise.shipping.demo.booking.DemoBookingBehavior behavior;
    BookingOwner owner;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SqliteDemoCarrier carrier;
    @Autowired @Qualifier("bookingDataSource") DataSource database;
    @Autowired @Qualifier("demoCarrierDataSource") DataSource remoteDatabase;

    @BeforeEach void reset() {
        owner = owners.ownerFor(null);
        behavior.setMode(com.enterprise.shipping.demo.booking.DemoBookingProperties.Mode.NORMAL);
        new JdbcTemplate(database).update("DELETE FROM shipment_bookings");
        new JdbcTemplate(remoteDatabase).update("DELETE FROM carrier_bookings");
    }
    String request() {
        return """
                {"orderReference":"order-http","carrierId":"fedex","serviceLevel":"Priority",
                 "parcel":{"originPostalCode":"L6K 3V3","destinationPostalCode":"M5V 2T6",
                    "weight":1,"weightUnit":"KG","dimensionUnit":"CM","length":10,"width":10,"height":10}}
                """;
    }
    ResponseEntity<String> post(String key, String body) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("Idempotency-Key", key);
        return client.postForEntity("http://127.0.0.1:" + PORT + "/api/shipping/bookings", new HttpEntity<>(body, headers), String.class);
    }
    JsonNode data(ResponseEntity<String> response) throws Exception { return mapper.readTree(response.getBody()).get("data"); }

    @Test void successfulCreationAndReplayHaveSameSnapshotAndOnlyOneSubmission() throws Exception {
        var first = post("http-success", request());
        var replay = post("http-success", request());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mapper.readTree(replay.getBody())).isEqualTo(mapper.readTree(first.getBody()));
        assertThat(first.getHeaders().getLocation()).isNotNull();
        assertThat(carrier.submissionCount(data(first).get("customerReference").asText())).isEqualTo(1);
        var label = client.getForEntity("http://127.0.0.1:" + PORT + data(first).get("labelUrl").asText(), String.class);
        assertThat(label.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(label.getBody()).contains("NOT VALID FOR POSTAGE");
    }

    @Test void equivalentImperialAndMetricMeasurementsReplayOneBooking() throws Exception {
        String imperial = request().replace("\"weight\":1", "\"weight\":2")
                .replace("\"weightUnit\":\"KG\"", "\"weightUnit\":\"LB\"")
                .replace("\"dimensionUnit\":\"CM\"", "\"dimensionUnit\":\"IN\"");
        String metric = request().replace("\"weight\":1", "\"weight\":0.90718474")
                .replace("\"length\":10", "\"length\":25.4")
                .replace("\"width\":10", "\"width\":25.4")
                .replace("\"height\":10", "\"height\":25.4");
        var first = post("unit-equivalence", imperial);
        var replay = post("unit-equivalence", metric);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mapper.readTree(replay.getBody())).isEqualTo(mapper.readTree(first.getBody()));
        assertThat(carrier.submissionCount(data(first).get("customerReference").asText())).isEqualTo(1);
        String saved = new JdbcTemplate(database).queryForObject("SELECT request_json FROM shipment_bookings", String.class);
        var parcel = mapper.readTree(saved).path("parcel");
        assertThat(parcel.path("lengthCm").decimalValue()).isEqualByComparingTo("25.4");
        assertThat(parcel.path("weightKg").decimalValue()).isEqualByComparingTo("0.90718474");
        assertThat(parcel.has("dimensionUnit")).isFalse();
    }

    @Test void configuredCarrierNeedsNoCodeOrRegexChange() throws Exception {
        var response = post("configured-carrier", request().replace("fedex", "PuRoLaToR"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(response).get("carrierId").asText()).isEqualTo("purolator");
        assertThat(carrier.submissionCount(data(response).get("customerReference").asText())).isEqualTo(1);
    }

    @Test void unconfiguredCarrierCreatesNoBooking() throws Exception {
        var response = post("unsupported-carrier", request().replace("fedex", "unknown-carrier"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(response.getBody()).get("errorCode").asText()).isEqualTo("UNSUPPORTED_CARRIER");
        assertThat(new JdbcTemplate(database).queryForObject("SELECT COUNT(*) FROM shipment_bookings", Integer.class)).isZero();
        assertThat(new JdbcTemplate(remoteDatabase).queryForObject("SELECT COUNT(*) FROM carrier_bookings", Integer.class)).isZero();
    }

    @Test void changedPayloadReturnsConflict() {
        post("http-conflict", request());
        var response = post("http-conflict", request().replace("order-http", "another-order"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("IDEMPOTENCY_CONFLICT");
    }

    @Test void actualHttpResponseLossRecoversWithoutBookingAgain() throws Exception {
        behavior.setMode(com.enterprise.shipping.demo.booking.DemoBookingProperties.Mode.TIMEOUT_AFTER_ACCEPT);
        var initial = post("http-timeout", request());
        assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(data(initial).get("status").asText()).isEqualTo("PENDING_INQUIRY");
        assertThat(post("http-timeout", request()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        new JdbcTemplate(database).update("UPDATE shipment_bookings SET next_inquiry_at=0");
        worker.reconcileBatch();
        var result = client.getForEntity("http://127.0.0.1:" + PORT + initial.getHeaders().getLocation(), String.class);
        assertThat(data(result).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(carrier.submissionCount(data(initial).get("customerReference").asText())).isEqualTo(1);
    }

    @Test void definitiveCarrierRejectionIsDifferentFromUnknownOutcome() throws Exception {
        behavior.setMode(com.enterprise.shipping.demo.booking.DemoBookingProperties.Mode.REJECTED);
        var rejected = post("http-rejected", request());
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(data(rejected).get("status").asText()).isEqualTo("FAILED");
        assertThat(post("http-rejected", request()).getBody()).isEqualTo(rejected.getBody());
        behavior.setMode(com.enterprise.shipping.demo.booking.DemoBookingProperties.Mode.UNAVAILABLE);
        var unavailable = post("http-unavailable", request());
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(data(unavailable).get("status").asText()).isEqualTo("PENDING_INQUIRY");
    }

    @Test void invalidRequestsDoNotCreateClaims() throws Exception {
        var missing = post(null, request());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var error = mapper.readTree(missing.getBody());
        assertThat(error.get("errorCode").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(error.get("message").asText()).isEqualTo("Missing required header: Idempotency-Key");

        for (String key : new String[] {"", "contains space"}) {
            var invalid = post(key, request());
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(mapper.readTree(invalid.getBody()).get("errorCode").asText()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
        }
        assertThat(post("bad-parcel", request().replace("\"weight\":1", "\"weight\":0")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new JdbcTemplate(database).queryForObject("SELECT COUNT(*) FROM shipment_bookings", Integer.class)).isZero();
        assertThat(new JdbcTemplate(remoteDatabase).queryForObject("SELECT COUNT(*) FROM carrier_bookings", Integer.class)).isZero();
    }

    @Test void cannotCallCarrierFromInsideAnOuterDatabaseTransaction() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status -> service.create(owner, "transaction-key",
                BookingWorkflowTest.command())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void unknownBookingIsNotFound() {
        assertThat(client.getForEntity("http://127.0.0.1:" + PORT + "/api/shipping/bookings/missing", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
