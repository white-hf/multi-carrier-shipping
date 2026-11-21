package com.enterprise.shipping.provider.sandbox.shipment;

import com.enterprise.shipping.provider.sandbox.shipment.dto.CreateShipmentRequest;
import com.enterprise.shipping.provider.sandbox.shipment.dto.CreateShipmentResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;

public interface ShipmentHttpClient {
    @PostExchange(url = "${shipping.provider.shipments-path:/rest/shipments}", contentType = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<CreateShipmentResponse>> create(@RequestBody CreateShipmentRequest request);
}
