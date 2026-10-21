package com.enterprise.shipping.demo.booking;

import com.enterprise.shipping.booking.domain.BookingCommand;
import com.enterprise.shipping.booking.domain.CarrierReceipt;
import com.enterprise.shipping.booking.port.CarrierBookingPort;
import com.enterprise.shipping.contracts.recovery.Recovery.Evidence;
import com.enterprise.shipping.contracts.recovery.Recovery.Reference;
import com.enterprise.shipping.reconciliation.port.CarrierInquiryPort;
import java.time.Duration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** HTTP port adapter; no automatic retry of a side-effecting submission. */
public final class DemoHttpBookingAdapter implements CarrierBookingPort, CarrierInquiryPort {
    public record Submission(@NotBlank @Size(max = 64) String customerReference, @NotNull BookingCommand command) {}
    private final WebClient client;
    private final Duration timeout;
    public DemoHttpBookingAdapter(WebClient client, Duration timeout) { this.client = client; this.timeout = timeout; }

    // The simulator has one account; real adapters resolve this persisted alias through a credential provider.
    @Override public CarrierReceipt submit(String context, String reference, BookingCommand command) {
        try {
            return client.post().uri("").header("Idempotency-Key", reference)
                    .bodyValue(new Submission(reference, command)).retrieve()
                    .bodyToMono(CarrierReceipt.class).timeout(timeout).block();
        } catch (WebClientResponseException.UnprocessableEntity rejected) {
            throw new Rejected();
        } catch (RuntimeException error) {
            throw new UnknownOutcome(error);
        }
    }

    @Override public Optional<Evidence> inquire(Reference reference, long timeoutMs) {
        try {
            return Optional.ofNullable(client.get().uri("/{reference}", reference.customerReference()).retrieve()
                    .bodyToMono(CarrierReceipt.class).timeout(Duration.ofMillis(timeoutMs)).block()).map(receipt ->
                    new Evidence(receipt.customerReference(), receipt.carrierBookingId(), receipt.trackingNumber(), receipt.labelUrl()));
        } catch (WebClientResponseException.NotFound missing) {
            return Optional.empty();
        } catch (RuntimeException error) {
            throw new UnknownOutcome(error);
        }
    }
}
