package com.enterprise.shipping.mock;

import com.enterprise.shipping.adapter.FedExCarrierAdapter.*;
import com.enterprise.shipping.adapter.UpsCarrierAdapter.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;

/** Deterministic local fixtures, not the actual carrier API schemas. */
@Profile("demo")
@RestController
@RequestMapping("/api/mock")
public class MockCarrierController {
    // Deliberately exceeds the configured carrier budgets to demonstrate timeout isolation.
    private static final long SLOW_RESPONSE_DELAY_MS = 1500;
    private static final long FEDEX_RESPONSE_DELAY_MS = 40;
    private static final long UPS_RESPONSE_DELAY_MS = 60;

    @Value("${shipping.mock.fedex-mode:success}") private String fedexMode;
    @Value("${shipping.mock.ups-mode:success}") private String upsMode;
    @PostMapping("/fedex/rates")
    public ResponseEntity<?> fedex() throws InterruptedException {
        Thread.sleep("slow".equals(fedexMode) ? SLOW_RESPONSE_DELAY_MS : FEDEX_RESPONSE_DELAY_MS);
        if ("error".equals(fedexMode)) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        return ResponseEntity.ok(new com.enterprise.shipping.adapter.FedExCarrierAdapter.RateResponse(
                "empty".equals(fedexMode) ? List.of() : List.of(
                new FedExService("Priority", "malformed".equals(fedexMode) ? null : new BigDecimal("18.50"), "CAD", 2),
                new FedExService("Economy", new BigDecimal("17.00"), "CAD", 4))));
    }
    @PostMapping("/ups/rates")
    public ResponseEntity<?> ups() throws InterruptedException {
        Thread.sleep("slow".equals(upsMode) ? SLOW_RESPONSE_DELAY_MS : UPS_RESPONSE_DELAY_MS);
        if ("error".equals(upsMode)) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        return ResponseEntity.ok(new com.enterprise.shipping.adapter.UpsCarrierAdapter.RateResponse(
                "empty".equals(upsMode) ? List.of() : List.of(new UpsService("Ground",
                "malformed".equals(upsMode) ? null : new BigDecimal("16.20"), "CAD", 3))));
    }
}
