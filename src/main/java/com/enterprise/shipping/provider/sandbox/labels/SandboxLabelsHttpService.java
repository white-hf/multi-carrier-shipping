package com.enterprise.shipping.provider.sandbox.labels;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Mono;

public interface SandboxLabelsHttpService {
    @GetExchange("${shipping.provider.shipments-path:/rest/shipments}/{shipmentId}/label")
    Mono<ResponseEntity<byte[]>> label(@PathVariable("shipmentId") String shipmentId);
}
