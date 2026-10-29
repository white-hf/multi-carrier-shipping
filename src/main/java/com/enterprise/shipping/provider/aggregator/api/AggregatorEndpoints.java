package com.enterprise.shipping.provider.aggregator.api;

import com.enterprise.shipping.provider.aggregator.AggregatorMapper;
import com.enterprise.shipping.provider.aggregator.api.codec.LabelResponseDecoder;
import com.enterprise.shipping.provider.aggregator.api.dto.CreateShipmentRequest;
import com.enterprise.shipping.provider.aggregator.api.dto.CreateShipmentResponse;
import com.enterprise.shipping.provider.aggregator.api.dto.RateApiRequest;
import com.enterprise.shipping.provider.aggregator.api.dto.RateApiResponse;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.LabelData;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.infrastructure.http.OutboundEndpoint;
import com.enterprise.shipping.shared.infrastructure.http.ResponseDecoder;
import com.enterprise.shipping.shared.infrastructure.http.StatusPolicy;
import java.util.function.Predicate;

/**
 * Declarative endpoint contracts for the external shipping aggregator protocol.
 * Adding a new operation requires only a DTO pair and an endpoint declaration here.
 */
public final class AggregatorEndpoints {

    private AggregatorEndpoints() {
    }

    public static OutboundEndpoint<RateApiRequest, RateApiResponse> rates() {
        return OutboundEndpoint.post(
                        "rates",
                        "/rest/rates",
                        RateApiRequest.class,
                        RateApiResponse.class)
                .decoder(
                        ResponseDecoder.json(RateApiResponse.class)
                                .validate(AggregatorMapper::validateCurrency))
                .statusPolicy(
                        StatusPolicy.<RateApiResponse>of(200)
                                .onExpected(404, RateApiResponse::empty)
                                .onError(
                                        400,
                                        () ->
                                                ShippingException.invalidInput(
                                                        "PROVIDER_REQUEST_REJECTED"))
                                .otherwiseThrow(StatusPolicy::standardReadFailure));
    }

    public static OutboundEndpoint<CreateShipmentRequest, CreateShipmentResponse> createShipment(
            String shipmentsPath) {
        return OutboundEndpoint.post(
                        "create",
                        shipmentsPath,
                        CreateShipmentRequest.class,
                        CreateShipmentResponse.class)
                .statusPolicy(
                        StatusPolicy.<CreateShipmentResponse>of(200, 201)
                                .onHandledError(400, CreateShipmentResponse::rejection)
                                .otherwiseThrow(
                                        status ->
                                                ShippingException.outcomeUnknown(
                                                        "SHIPMENT_OUTCOME_UNKNOWN"))
                                .onDecodeFailure(
                                        error ->
                                                ShippingException.outcomeUnknown(
                                                        "SHIPMENT_OUTCOME_UNKNOWN")));
    }

    public static OutboundEndpoint<Void, LabelData> label(
            String shipmentsPath, Predicate<String> allowedUrl) {
        return OutboundEndpoint.<Void, LabelData>get(
                        "labels",
                        shipmentsPath + "/{shipmentId}/label",
                        LabelData.class)
                .decoder(new LabelResponseDecoder(allowedUrl))
                .statusPolicy(
                        StatusPolicy.<LabelData>of2xx()
                                .onExpected(202, () -> null)
                                .onExpected(404, () -> null)
                                .otherwiseThrow(StatusPolicy::standardReadFailure));
    }
}
