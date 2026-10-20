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
        registry.add("shipping.booking.database-path", () -> DIRECTORY.resolve("app.db").toString());
        registry.add("shipping.booking.carrier-database-path", () -> DIRECTORY.resolve("carrier.db").toString());
        registry.add("shipping.booking.carrier-base-url", () -> "http://127.0.0.1:" + PORT + "/api/mock/bookings");
    }
    @Autowired TestRestTemplate client;
    @Autowired ObjectMapper mapper;
    @Autowired BookingReconciliationWorker worker;
    @Autowired BookingService service;
    @Autowired BookingOwner owner;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SqliteDemoCarrier carrier;
    @Autowired @Qualifier("bookingDataSource") DataSource database;
    @Autowired @Qualifier("demoCarrierDataSource") DataSource remoteDatabase;

    @BeforeEach void reset() {
        new JdbcTemplate(database).update("DELETE FROM shipment_bookings");
        new JdbcTemplate(remoteDatabase).update("DELETE FROM carrier_bookings");
    }
    String request(String scenario) {
        return """
                {"orderReference":"order-http","carrierId":"fedex","serviceLevel":"Priority",
                 "parcel":{"originPostalCode":"L6K 3V3","destinationPostalCode":"M5V 2T6",
                    "weightKg":1,"lengthCm":10,"widthCm":10,"heightCm":10},"demoScenario":"%s"}
                """.formatted(scenario);
    }
    ResponseEntity<String> post(String key, String body) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("Idempotency-Key", key);
        return client.postForEntity("http://127.0.0.1:" + PORT + "/api/shipping/bookings", new HttpEntity<>(body, headers), String.class);
    }
    JsonNode data(ResponseEntity<String> response) throws Exception { return mapper.readTree(response.getBody()).get("data"); }

    @Test void successfulCreationAndReplayHaveSameSnapshotAndOnlyOneSubmission() throws Exception {
        var first = post("http-success", request("NORMAL"));
        var replay = post("http-success", request("NORMAL"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mapper.readTree(replay.getBody())).isEqualTo(mapper.readTree(first.getBody()));
        assertThat(first.getHeaders().getLocation()).isNotNull();
        assertThat(carrier.submissionCount(data(first).get("customerReference").asText())).isEqualTo(1);
        var label = client.getForEntity("http://127.0.0.1:" + PORT + data(first).get("labelUrl").asText(), String.class);
        assertThat(label.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(label.getBody()).contains("NOT VALID FOR POSTAGE");
    }

    @Test void changedPayloadReturnsConflict() {
        post("http-conflict", request("NORMAL"));
        var response = post("http-conflict", request("NORMAL").replace("order-http", "another-order"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("IDEMPOTENCY_CONFLICT");
    }

    @Test void actualHttpResponseLossRecoversWithoutBookingAgain() throws Exception {
        var initial = post("http-timeout", request("TIMEOUT_AFTER_ACCEPT"));
        assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(data(initial).get("status").asText()).isEqualTo("PENDING_INQUIRY");
        assertThat(post("http-timeout", request("TIMEOUT_AFTER_ACCEPT")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        new JdbcTemplate(database).update("UPDATE shipment_bookings SET next_inquiry_at=0");
        worker.reconcileBatch();
        var result = client.getForEntity("http://127.0.0.1:" + PORT + initial.getHeaders().getLocation(), String.class);
        assertThat(data(result).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(carrier.submissionCount(data(initial).get("customerReference").asText())).isEqualTo(1);
    }

    @Test void definitiveCarrierRejectionIsDifferentFromUnknownOutcome() throws Exception {
        var rejected = post("http-rejected", request("REJECTED"));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(data(rejected).get("status").asText()).isEqualTo("FAILED");
        assertThat(post("http-rejected", request("REJECTED")).getBody()).isEqualTo(rejected.getBody());
        var unavailable = post("http-unavailable", request("UNAVAILABLE"));
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(data(unavailable).get("status").asText()).isEqualTo("PENDING_INQUIRY");
    }

    @Test void invalidRequestsDoNotCreateClaims() throws Exception {
        var missing = post(null, request("NORMAL"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var error = mapper.readTree(missing.getBody());
        assertThat(error.get("errorCode").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(error.get("message").asText()).isEqualTo("Missing required header: Idempotency-Key");

        for (String key : new String[] {"", "contains space"}) {
            var invalid = post(key, request("NORMAL"));
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(mapper.readTree(invalid.getBody()).get("errorCode").asText()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
        }
        assertThat(post("bad-parcel", request("NORMAL").replace("\"weightKg\":1", "\"weightKg\":0")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new JdbcTemplate(database).queryForObject("SELECT COUNT(*) FROM shipment_bookings", Integer.class)).isZero();
        assertThat(new JdbcTemplate(remoteDatabase).queryForObject("SELECT COUNT(*) FROM carrier_bookings", Integer.class)).isZero();
    }

    @Test void cannotCallCarrierFromInsideAnOuterDatabaseTransaction() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status -> service.create(owner, "transaction-key",
                BookingWorkflowTest.command(BookingCommand.DemoScenario.NORMAL))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void unknownBookingIsNotFound() {
        assertThat(client.getForEntity("http://127.0.0.1:" + PORT + "/api/shipping/bookings/missing", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
