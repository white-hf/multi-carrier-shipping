package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.provider.sandbox.config.ProviderProperties;
import com.enterprise.shipping.provider.sandbox.model.ProviderModels.*;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClient;
import com.enterprise.shipping.shared.infrastructure.http.OutboundClientRegistry;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.domain.*;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Handles Sandbox Shipping Provider API requests, payload parsing, and unit conversion.
 *
 * Uses one provider transport pool and the default resilience group for the core workflow.
 * Shared capacity/circuit state reduces maintenance but lets rate failures affect booking/labels.
 * Add a configured logical group for an independently failing or lower-priority workload;
 * group isolation does not reserve connections in the shared pool. No automatic write retry.
 */
public class SandboxProviderClient {

    private final ProviderProperties p;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SandboxPayloadMapper payloads;

    private final OutboundClient outbound;

    public SandboxProviderClient(
            ProviderProperties p,
            ObjectMapper mapper,
            Clock clock,
            OutboundClientRegistry registry) {
        this.p = p;
        this.mapper = mapper;
        this.clock = clock;
        this.payloads = new SandboxPayloadMapper(p.collectionSeconds());
        this.outbound =
                registry.register(
                        p.providerId(),
                        p.baseUrl(),
                        Set.of("rates", "create", "labels"),
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
        return outbound.execute(
                "rates",
                deadline,
                exchange -> {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw ShippingException.dependencyTimeout("PROVIDER_TIMEOUT");
                    }

                    var response = post(exchange, "/rest/rates", payloads.rateRequest(intent));
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw readFailure(response.getStatusCode().value());
                    }

                    try {
                        var body = mapper.readTree(response.getBody());
                        var available = body.get("availableRates");
                        if (available == null || !available.isArray()) {
                            throw protocol();
                        }

                        List<RateOffer> result = new ArrayList<>();
                        for (var q : available) {
                            var money = q.path("totalCharge");
                            if (!money.path("amount").isIntegralNumber()
                                    || !money.path("amount").canConvertToLong()
                                    || money.path("amount").asLong() < 0) {
                                throw protocol();
                            }

                            String currency = SandboxPayloadMapper.text(money, "currency");
                            if (!"CAD".equals(currency)) {
                                throw protocol();
                            }

                            // Handles missing or Days-valued transit units
                            Integer days =
                                    q.hasNonNull("transitDays")
                                                    && q.path("transitDays").isIntegralNumber()
                                                    && (!q.hasNonNull("transitUnit")
                                                            || "Days"
                                                                    .equalsIgnoreCase(
                                                                            q.path("transitUnit")
                                                                                    .asText()))
                                            ? q.get("transitDays").asInt(-1)
                                            : null;
                            if (days != null && days < 0) {
                                throw protocol();
                            }

                            result.add(
                                    new RateOffer(
                                            p.providerId(),
                                            p.accountAlias(),
                                            SandboxPayloadMapper.text(q, "quoteId"),
                                            SandboxPayloadMapper.text(q, "carrierId"),
                                            q.path("carrierName").asText(null),
                                            SandboxPayloadMapper.text(q, "serviceId"),
                                            q.path("serviceName").asText(null),
                                            money.path("amount").longValue(),
                                            currency,
                                            days,
                                            clock.millis() + p.quoteLifetimeSeconds() * 1000L));
                        }
                        return result;
                    } catch (java.io.IOException e) {
                        throw protocol();
                    }
                });
    }

    @MonitorCarrierApi(carrier = "shipping-provider")
    public CreationOutcome create(CreateSubmission submission) {
        requireContext(submission.providerId(), submission.accountAlias());
        String quote = submission.providerQuoteId();

        return outbound.execute(
                "create",
                OutboundDeadline.after(p.createTimeout()),
                exchange -> {
                    // Quote-only mode: Adding reference or parcel payloads can alter billing and
                    // price guarantees.
                    var response = post(exchange, p.shipmentsPath(), Map.of("quoteId", quote));
                    int status = response.getStatusCode().value();

                    if (status == 400 || status == 401 || status == 403 || status == 422) {
                        return CreationOutcome.rejection();
                    }
                    if (status != 200 && status != 201) {
                        throw ShippingException.outcomeUnknown("SHIPMENT_OUTCOME_UNKNOWN");
                    }

                    try {
                        var body = mapper.readTree(response.getBody());
                        String id = SandboxPayloadMapper.shipmentId(body);
                        String tracking =
                                body.path("trackingNumbers").isArray()
                                                && body.path("trackingNumbers").size() > 0
                                        ? body.path("trackingNumbers").get(0).asText()
                                        : null;
                        String url = body.path("labelUrl").asText(null);

                        return new CreationOutcome(
                                id, tracking, allowedUrl(url) ? url : null, false);
                    } catch (java.io.IOException e) {
                        throw protocol();
                    }
                });
    }

    @MonitorCarrierApi(carrier = "shipping-provider")
    public LabelData fetch(LabelIdentity identity) {
        requireContext(identity.providerId(), identity.accountAlias());
        String id = identity.shipmentId();
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw protocol();
        }

        return outbound.execute(
                "labels",
                OutboundDeadline.after(p.readTimeout()),
                exchange -> {
                    var response = exchange.get(p.shipmentsPath() + "/" + id + "/label");
                    if (response == null) {
                        throw protocol();
                    }

                    int status = response.getStatusCode().value();
                    if (status == 202 || status == 404) {
                        return null;
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw readFailure(status);
                    }

                    var content = response.getHeaders().getContentType();
                    byte[] bytes = response.getBody();
                    if (bytes == null || bytes.length == 0) {
                        return null;
                    }

                    if (content != null && MediaType.APPLICATION_PDF.isCompatibleWith(content)) {
                        return new LabelData(bytes, "application/pdf", null);
                    }

                    if (content != null && MediaType.APPLICATION_JSON.isCompatibleWith(content)) {
                        try {
                            String url = mapper.readTree(bytes).path("labelUrl").asText(null);
                            if (allowedUrl(url)) {
                                return new LabelData(null, null, url);
                            }
                        } catch (java.io.IOException ignored) {
                            // Ignored: Invalid payload structure is a protocol failure

                          }
                    }
                    throw protocol();
                });
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

    private ResponseEntity<byte[]> post(
            OutboundClient.Exchange exchange, String path, Object body) {
        var response = exchange.post(path, body);
        if (response == null || response.getBody() == null) {
            throw protocol();
        }
        return response;
    }

    private static ShippingException protocol() {
        return ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE");
    }

    private static ShippingException readFailure(int status) {
        return ShippingException.dependencyFailure(
                status == 401 || status == 403
                        ? "PROVIDER_AUTHENTICATION_FAILED"
                        : "PROVIDER_UNAVAILABLE");
    }
}
