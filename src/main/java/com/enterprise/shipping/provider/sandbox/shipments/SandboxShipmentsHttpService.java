package com.enterprise.shipping.provider.sandbox.shipments;

import com.enterprise.shipping.provider.sandbox.shipments.dto.CreateShipmentRequest;
import com.enterprise.shipping.provider.sandbox.shipments.dto.CreateShipmentResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;

public interface SandboxShipmentsHttpService {
    @PostExchange(url = "${shipping.provider.shipments-path:/rest/shipments}", contentType = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<CreateShipmentResponse>> create(@RequestBody CreateShipmentRequest request);
}
