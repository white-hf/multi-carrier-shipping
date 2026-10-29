package com.enterprise.shipping.provider.aggregator;

import com.enterprise.shipping.provider.aggregator.api.AggregatorEndpoints;
import com.enterprise.shipping.provider.aggregator.api.dto.CreateShipmentRequest;
import com.enterprise.shipping.provider.aggregator.api.dto.CreateShipmentResponse;
import com.enterprise.shipping.provider.aggregator.api.dto.RateApiRequest;
import com.enterprise.shipping.provider.aggregator.api.dto.RateApiResponse;
import com.enterprise.shipping.provider.aggregator.config.ProviderProperties;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.Capabilities;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.CreateSubmission;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.CreationOutcome;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.LabelData;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.LabelIdentity;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.RateOffer;
import com.enterprise.shipping.provider.aggregator.model.ProviderModels.Support;
import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingIntent;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClient;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.http.OutboundEndpoint;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strongly-typed RPC-style client for the external shipping aggregator protocol.
 *
 * Uses one provider transport pool and the default resilience group for the core workflow.
 * Transport, payload serialization, status classification, and response validation are
 * delegated to declarative OutboundEndpoint contracts.
 */
public class AggregatorProviderClient {

    private final ProviderProperties p;
    private final Clock clock;
    private final AggregatorMapper mapper;
    private final OutboundEndpoint<RateApiRequest, RateApiResponse> ratesEndpoint;
    private final OutboundEndpoint<CreateShipmentRequest, CreateShipmentResponse> createEndpoint;
    private final OutboundEndpoint<Void, LabelData> labelEndpoint;
    private final OutboundClient outbound;

    public AggregatorProviderClient(
            ProviderProperties p,
            Clock clock,
            OutboundClientRegistry registry) {
        this.p = p;
        this.clock = clock;
        this.mapper = new AggregatorMapper(p.collectionSeconds());
        this.ratesEndpoint = AggregatorEndpoints.rates();
        this.createEndpoint = AggregatorEndpoints.createShipment(p.shipmentsPath());
        this.labelEndpoint = AggregatorEndpoints.label(p.shipmentsPath(), this::allowedUrl);
        this.outbound =
                registry.registerEndpoints(
                        p.providerId(),
                        p.baseUrl(),
                        List.of(ratesEndpoint, createEndpoint, labelEndpoint),
                        builder ->
                                builder.defaultHeaders(
                                        headers ->
                                                headers.setBasicAuth(p.username(), p.password())));
    }

    public Capabilities capabilities(String providerId, String accountAlias) {
        if (!p.providerId().equals(providerId) || !p.accountAlias().equals(accountAlias)) {
            return new Capabilities(Support.UNVERIFIED, Support.UNVERIFIED);
        }
        // No documented authoritative reference lookup or guaranteed safe creation retry.
        return new Capabilities(Support.UNSUPPORTED, Support.UNVERIFIED);
    }

    @MonitorCarrierApi(carrier = "shipping-provider")
    public List<RateOffer> fetchRates(ShippingIntent intent, long deadline) {
        // 1. Build the typed rate request and invoke the declarative rates endpoint
        RateApiResponse response =
                outbound.invoke(ratesEndpoint, mapper.toRateRequest(intent), deadline);

        // 2. Map validated protocol offers into neutral rate offers
        long expiresAt = clock.millis() + p.quoteLifetimeSeconds() * 1000L;
        return mapper.toRateOffers(response, p.providerId(), p.accountAlias(), expiresAt);
    }

    @MonitorCarrierApi(carrier = "shipping-provider")
    public CreationOutcome create(CreateSubmission submission) {
        // 1. Verify provider and account routing context before external dispatch
        requireContext(submission.providerId(), submission.accountAlias());

        // 2. Invoke the declarative shipment creation endpoint in quote-only mode
        CreateShipmentResponse response =
                outbound.invoke(
                        createEndpoint,
                        new CreateShipmentRequest(submission.providerQuoteId()),
                        OutboundDeadline.after(p.createTimeout()));

        // 3. Map the creation outcome and filter any inline label URL through the allowlist
        return mapper.toCreationOutcome(response, this::allowedUrl);
    }

    @MonitorCarrierApi(carrier = "shipping-provider")
    public LabelData fetch(LabelIdentity identity) {
        // 1. Verify provider and account routing context and validate shipment identifier syntax
        requireContext(identity.providerId(), identity.accountAlias());
        String id = identity.shipmentId();
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
        }

        // 2. Invoke the declarative label endpoint with explicit path variable binding
        return outbound.invoke(
                labelEndpoint,
                Map.of("shipmentId", id),
                null,
                OutboundDeadline.after(p.readTimeout()));
    }

    private void requireContext(String providerId, String accountAlias) {
        if (!p.providerId().equals(providerId) || !p.accountAlias().equals(accountAlias)) {
            throw ShippingException.integrationUnavailable("PROVIDER_CONTEXT_UNAVAILABLE");
        }
    }

    public boolean allowedUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme())
                    && uri.getUserInfo() == null
                    && uri.getHost() != null
                    && p.labelHosts().contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
