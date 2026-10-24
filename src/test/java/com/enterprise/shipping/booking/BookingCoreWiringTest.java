package com.enterprise.shipping.booking;

import com.enterprise.shipping.booking.application.BookingCommand;

import com.enterprise.shipping.Application;
import com.enterprise.shipping.booking.domain.*;
import com.enterprise.shipping.booking.port.CarrierBookingPort;
import com.enterprise.shipping.booking.contract.recovery.Recovery.Evidence;
import com.enterprise.shipping.booking.contract.recovery.Recovery.Reference;
import com.enterprise.shipping.reconciliation.port.CarrierInquiryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Application.class, properties = {
        "shipping.quote.enabled=false",
        "shipping.booking.enabled-carriers[0]=test-carrier", "shipping.reconciliation.scheduling-enabled=false", "shipping.booking.carrier-context-reference=test-account",
        "shipping.market.default-country=US", "shipping.market.comparison-currency=USD",
        "shipping.market.postal-code-patterns.US=[0-9]{5}"})
@AutoConfigureMockMvc
@Import(BookingCoreWiringTest.TestCarrierConfiguration.class)
@DirtiesContext
class BookingCoreWiringTest {
    static final Path DATABASE;
    static {
        try { DATABASE = Files.createTempDirectory("core-booking-").resolve("bookings.db"); }
        catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("shipping.booking.database-path", () -> DATABASE.toString());
    }
    static final String REQUEST = """
            {"orderReference":"order-core","carrierId":"test-carrier","serviceLevel":"Ground",
             "parcel":{"originPostalCode":"90210","destinationPostalCode":"10001",
              "weight":1,"weightUnit":"KG","dimensionUnit":"CM","length":10,"width":10,"height":10}}
            """;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource database;
    @Autowired ApplicationContext context;
    @Autowired TestCarrier carrier;
    @BeforeEach void reset() {
        new JdbcTemplate(database).update("DELETE FROM shipment_bookings");
        carrier.calls.set(0);
    }

    @Test void coreRunsWithoutAnyDemoBeanAndRequiresAuthentication() throws Exception {
        assertThat(context.getBean(com.enterprise.shipping.reconciliation.application.ReconciliationWorker.class)).isNotNull();
        assertThat(context.getBean(com.enterprise.shipping.reconciliation.application.ReconciliationDiscovery.class)).isNotNull();
        assertThat(context.getBeansOfType(com.enterprise.shipping.reconciliation.application.ReconciliationScheduler.class)).isEmpty();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type != null) assertThat(type.getPackageName()).doesNotStartWith("com.enterprise.shipping.demo");
        }
        mvc.perform(post("/api/shipping/bookings").header("Idempotency-Key", "auth-required")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        assertThat(carrier.calls).hasValue(0);
        mvc.perform(get("/api/mock/bookings/reference")).andExpect(status().isNotFound());
    }

    @Test void authenticatedOwnershipIsResolvedPerRequest() throws Exception {
        var first = mvc.perform(post("/api/shipping/bookings").principal(() -> "account-a")
                .header("Idempotency-Key", "same-key").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isCreated()).andReturn();
        String id = mapper.readTree(first.getResponse().getContentAsString()).path("data").path("bookingId").asText();
        mvc.perform(get("/api/shipping/bookings/" + id).principal(() -> "account-a")).andExpect(status().isOk());
        mvc.perform(get("/api/shipping/bookings/" + id).principal(() -> "account-b")).andExpect(status().isNotFound());
        mvc.perform(post("/api/shipping/bookings").principal(() -> "account-b")
                .header("Idempotency-Key", "same-key").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isCreated());
        assertThat(carrier.calls).hasValue(2);
    }

    @Test void simulatorFieldsAreRejectedRatherThanIgnored() throws Exception {
        String injected = "{\"demoScenario\":\"REJECTED\"," + REQUEST.strip().substring(1);
        mvc.perform(post("/api/shipping/bookings").principal(() -> "account-a")
                .header("Idempotency-Key", "simulation-field").contentType(MediaType.APPLICATION_JSON).content(injected))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        assertThat(carrier.calls).hasValue(0);
    }

    @Test void countrySpecificPostalPolicyIsEnforcedBeforeSubmission() throws Exception {
        mvc.perform(post("/api/shipping/bookings").principal(() -> "account-a")
                .header("Idempotency-Key", "wrong-postal").contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST.replace("90210", "L6K3V3")))
                .andExpect(status().isBadRequest());
        assertThat(carrier.calls).hasValue(0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestCarrierConfiguration {
        @Bean TestCarrier testCarrier() { return new TestCarrier(); }
    }
    static class TestCarrier implements CarrierBookingPort, CarrierInquiryPort {
        final AtomicInteger calls = new AtomicInteger();
        public CarrierReceipt submit(String context, String reference, BookingCommand command) {
            calls.incrementAndGet();
            return new CarrierReceipt(reference, reference + "-id", "tracking", "/test-label");
        }
        public Optional<Evidence> inquire(Reference reference, long timeoutMs) { return Optional.empty(); }
    }
}
