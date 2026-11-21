package com.enterprise.shipping.provider.sandbox.rate;

import com.enterprise.shipping.provider.sandbox.rate.dto.RateApiRequest;
import com.enterprise.shipping.provider.sandbox.rate.dto.RateApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;

/** Spring owns request binding and typed response decoding; the operation owns provider semantics. */
public interface RateHttpClient {
    @PostExchange(url = "/rest/rates", contentType = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<RateApiResponse>> rates(@RequestBody RateApiRequest request);
}
