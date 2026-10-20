package com.enterprise.shipping.booking.infrastructure.carrier;

import com.enterprise.shipping.booking.domain.BookingCommand;
import com.enterprise.shipping.booking.domain.CarrierReceipt;
import com.enterprise.shipping.booking.port.CarrierBookingPort;
import java.time.Duration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** HTTP port adapter; no automatic retry of a side-effecting submission. */
public final class HttpCarrierBookingAdapter implements CarrierBookingPort {
    public record Submission(@NotBlank @Size(max = 64) String customerReference, @NotNull BookingCommand command) {}
    private final WebClient client;
    private final Duration timeout;
    public HttpCarrierBookingAdapter(WebClient client, Duration timeout) { this.client = client; this.timeout = timeout; }

    @Override public CarrierReceipt submit(String reference, BookingCommand command) {
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

    @Override public Optional<CarrierReceipt> inquire(String reference) {
        try {
            return Optional.ofNullable(client.get().uri("/{reference}", reference).retrieve()
                    .bodyToMono(CarrierReceipt.class).timeout(timeout).block());
        } catch (WebClientResponseException.NotFound missing) {
            return Optional.empty();
        } catch (RuntimeException error) {
            throw new UnknownOutcome(error);
        }
    }
}
