package com.enterprise.shipping.shared.api;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingException.Category;
import com.enterprise.shipping.shared.domain.ShippingException.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Verify the public wire contract while application exceptions remain protocol-independent. */
class ShippingExceptionHandlerTest {
    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new FailureEndpoint())
                    .setControllerAdvice(new ShippingExceptionHandler())
                    .build();

    @RestController
    static class FailureEndpoint {
        @GetMapping("/failure")
        public void fail(
                @RequestParam Category category,
                @RequestParam(required = false) Resource resource) {
            var error =
                    new ShippingException(
                            category == Category.LOCAL_CAPACITY
                                    ? "SHIPMENT_SUBMISSION_NOT_DISPATCHED"
                                    : "TEST_FAILURE",
                            category);
            if (resource != null) {
                error = error.forOperation(resource, "operation-1");
            }
            throw error;
        }
    }

    @ParameterizedTest
    @CsvSource({
        "INVALID_INPUT,400",
        "RESOURCE_MISSING,404",
        "STATE_CONFLICT,409",
        "DEPENDENCY_FAILURE,502",
        "DEPENDENCY_TIMEOUT,504",
        "LOCAL_CAPACITY,503",
        "STORAGE_FAILURE,503",
        "INTEGRATION_UNAVAILABLE,503",
        "OUTCOME_UNKNOWN,202"
    })
    void mapsEveryFailureCategoryWithoutExposingInternalMetadata(
            Category category, int expectedStatus) throws Exception {
        mvc.perform(get("/failure").param("category", category.name()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.category").doesNotExist())
                .andExpect(jsonPath("$.operation").doesNotExist())
                .andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @CsvSource({"QUOTE_REQUEST,/api/quotes/operation-1", "BOOKING,/api/bookings/operation-1"})
    void buildsTheOriginalOperationLocatorOnlyAtTheApiBoundary(Resource resource, String path)
            throws Exception {
        mvc.perform(
                        get("/failure")
                                .param("category", "STATE_CONFLICT")
                                .param("resource", resource.name()))
                .andExpect(status().isConflict())
                .andExpect(header().string("Location", path))
                .andExpect(jsonPath("$.operation.id").value("operation-1"))
                .andExpect(jsonPath("$.operation.statusUrl").value(path))
                .andExpect(jsonPath("$.operation.resource").doesNotExist());
    }

    @Test
    void knownUnsentBookingRetainsRetryGuidanceAndItsBookingLocator() throws Exception {
        mvc.perform(
                        get("/failure")
                                .param("category", "LOCAL_CAPACITY")
                                .param("resource", "BOOKING"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(header().string("Location", "/api/bookings/operation-1"))
                .andExpect(jsonPath("$.code").value("SHIPMENT_SUBMISSION_NOT_DISPATCHED"));
    }

    @Test
    void attachingResourceIdentityDoesNotMutateTheOriginalExceptionOrChangeFailureClassification() {
        var original = ShippingException.dependencyTimeout("PROVIDER_TIMEOUT");
        var quote = original.forQuoteRequest("quote-1");
        var booking = quote.forBooking("booking-1");
        assertThat(original.operation()).isNull();
        assertThat(quote.operation())
                .isEqualTo(new ShippingException.Operation(Resource.QUOTE_REQUEST, "quote-1"));
        assertThat(booking.operation())
                .isEqualTo(new ShippingException.Operation(Resource.BOOKING, "booking-1"));
        assertThat(booking.category()).isEqualTo(Category.DEPENDENCY_TIMEOUT);
        assertThat(booking.code()).isEqualTo(original.code());
    }
}
