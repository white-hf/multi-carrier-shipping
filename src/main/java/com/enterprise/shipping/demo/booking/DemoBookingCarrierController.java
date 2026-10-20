package com.enterprise.shipping.demo.booking;

import com.enterprise.shipping.booking.infrastructure.carrier.HttpCarrierBookingAdapter.Submission;
import com.enterprise.shipping.booking.domain.BookingCommand.DemoScenario;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("demo")
@ConditionalOnProperty(name = "shipping.booking.enabled", havingValue = "true")
@RequestMapping("/api/mock/bookings")
public class DemoBookingCarrierController {
    private static final Logger log = LoggerFactory.getLogger(DemoBookingCarrierController.class);
    private static final long RESPONSE_LOSS_DELAY_MS = 1500;
    private final SqliteDemoCarrier carrier;
    public DemoBookingCarrierController(SqliteDemoCarrier carrier) { this.carrier = carrier; }

    @PostMapping
    public ResponseEntity<?> submit(@Valid @RequestBody Submission request) throws InterruptedException {
        DemoScenario scenario = request.command().demoScenario();
        if (scenario == DemoScenario.REJECTED) return ResponseEntity.unprocessableEntity().build();
        if (scenario == DemoScenario.UNAVAILABLE) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        var receipt = carrier.accept(request.customerReference(), request.command());
        log.info("Simulated carrier accepted reference={}", request.customerReference());
        // Commit remote state first, then withhold the response beyond the caller's deadline.
        if (scenario == DemoScenario.TIMEOUT_AFTER_ACCEPT) Thread.sleep(RESPONSE_LOSS_DELAY_MS);
        return ResponseEntity.status(HttpStatus.CREATED).body(receipt);
    }

    @GetMapping("/{reference}")
    public ResponseEntity<?> inquire(@PathVariable String reference) {
        return carrier.find(reference).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{reference}/label", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> label(@PathVariable String reference) {
        return carrier.find(reference)
                .map(receipt -> ResponseEntity.ok("SIMULATED SHIPPING LABEL — NOT VALID FOR POSTAGE\n"
                        + "Tracking: " + receipt.trackingNumber() + "\nReference: " + receipt.customerReference()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
