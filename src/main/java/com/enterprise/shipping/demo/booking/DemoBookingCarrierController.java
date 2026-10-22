package com.enterprise.shipping.demo.booking;

import com.enterprise.shipping.demo.booking.DemoHttpBookingAdapter.Submission;
import com.enterprise.shipping.demo.booking.DemoBookingProperties.Mode;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("demo")
@RequestMapping("/api/mock/bookings")
public class DemoBookingCarrierController {
    private static final Logger log = LoggerFactory.getLogger(DemoBookingCarrierController.class);
    private static final long RESPONSE_LOSS_DELAY_MS = 1500;
    private final SqliteDemoCarrier carrier;
    private final DemoBookingBehavior behavior;
    public DemoBookingCarrierController(SqliteDemoCarrier carrier, DemoBookingBehavior behavior) {
        this.carrier = carrier;
        this.behavior = behavior;
    }

    @PostMapping
    public ResponseEntity<?> submit(@Valid @RequestBody Submission request) throws InterruptedException {
        Mode scenario = behavior.mode();
        if (scenario == Mode.REJECTED) return ResponseEntity.unprocessableEntity().build();
        if (scenario == Mode.UNAVAILABLE) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        var receipt = carrier.accept(request.customerReference(), request.command());
        log.info("Simulated carrier accepted reference={}", request.customerReference());
        // Commit remote state first, then withhold the response beyond the caller's deadline.
        if (scenario == Mode.TIMEOUT_AFTER_ACCEPT) Thread.sleep(RESPONSE_LOSS_DELAY_MS);
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
