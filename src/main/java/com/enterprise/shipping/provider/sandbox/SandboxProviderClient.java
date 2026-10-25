package com.enterprise.shipping.provider.sandbox;

import com.enterprise.shipping.provider.sandbox.config.ProviderProperties;
import com.enterprise.shipping.provider.sandbox.model.ProviderModels.*;
import com.enterprise.shipping.shared.infrastructure.http.OutboundOperation;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.domain.*;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import com.enterprise.shipping.shared.infrastructure.http.CarrierHttpClientFactory;
import com.fasterxml.jackson.databind.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.*;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Handles Sandbox Shipping Provider API requests, payload parsing, and unit conversion.
 * 
 * Configures dedicated, isolated connection pools, bulkheads, and circuit breakers 
 * for each operation type (rates, creates, labels) to prevent downstream rate timeouts 
 * or failures from exhausting creation/booking resources.
 */
public class SandboxProviderClient {

    private final ProviderProperties p;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SandboxPayloadMapper payloads;

    private final WebClient rates;
    private final WebClient creates;
    private final WebClient reads;

    private final Map<String, OutboundOperation> operations = new HashMap<>();

    public SandboxProviderClient(ProviderProperties p, ObjectMapper mapper, Clock clock) {
        this.p = p;
        this.mapper = mapper;
        this.clock = clock;
        this.payloads = new SandboxPayloadMapper(p.collectionSeconds());

        try {
            this.rates = client("rates", p.readConcurrency(), p.rateTimeout());
            this.creates = client("create", p.createConcurrency(), p.createTimeout());
            this.reads = client("labels", p.readConcurrency(), p.readTimeout());
        } catch (RuntimeException failure) {
            close(); // Release earlier operations if a later client cannot initialize.
            throw failure;
        }
    }

    private WebClient client(String operation, int capacity, Duration timeout) {
        // Shared construction, isolated instances. Automatic transport retries stay disabled.
        var resource = new OutboundOperation("provider-" + operation, p.baseUrl(), capacity, timeout,
                4 * 1024 * 1024, CarrierHttpClientFactory.TransportRetry.DISABLED,
                e -> e instanceof ShippingException s && s.isClientError());
        operations.put(operation, resource);
        return resource.client().mutate()
                .defaultHeaders(h -> h.setBasicAuth(p.username(), p.password())).build();
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
        return guarded("rates", () -> {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw ShippingException.gatewayTimeout("PROVIDER_TIMEOUT");
            }

            var response = post(rates, "/rest/rates", payloads.rateRequest(intent), deadline);
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
                    if (!money.path("amount").isIntegralNumber() || !money.path("amount").canConvertToLong() || money.path("amount").asLong() < 0) {
                        throw protocol();
                    }

                    String currency = SandboxPayloadMapper.text(money, "currency");
                    if (!"CAD".equals(currency)) {
                        throw protocol();
                    }

                    // Handles missing or Days-valued transit units
                    Integer days = q.hasNonNull("transitDays") && q.path("transitDays").isIntegralNumber()
                            && (!q.hasNonNull("transitUnit") || "Days".equalsIgnoreCase(q.path("transitUnit").asText()))
                            ? q.get("transitDays").asInt(-1)
                            : null;
                    if (days != null && days < 0) {
                        throw protocol();
                    }

                    result.add(new RateOffer(
                            p.providerId(), p.accountAlias(),
                            SandboxPayloadMapper.text(q, "quoteId"), SandboxPayloadMapper.text(q, "carrierId"), q.path("carrierName").asText(null),
                            SandboxPayloadMapper.text(q, "serviceId"), q.path("serviceName").asText(null),
                            money.path("amount").longValue(), currency, days,
                            clock.millis() + p.quoteLifetimeSeconds() * 1000L
                    ));
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

        return guarded("create", () -> {
            // Quote-only mode: Adding reference or parcel payloads can alter billing and price guarantees.
            var response = post(creates, p.shipmentsPath(), Map.of("quoteId", quote), OutboundDeadline.after(p.createTimeout()));
            int status = response.getStatusCode().value();

            if (status == 400 || status == 401 || status == 403 || status == 422) {
                return CreationOutcome.rejection();
            }
            if (status != 200 && status != 201) {
                throw ShippingException.accepted("SHIPMENT_OUTCOME_UNKNOWN");
            }

            try {
                var body = mapper.readTree(response.getBody());
                String id = SandboxPayloadMapper.shipmentId(body);
                String tracking = body.path("trackingNumbers").isArray() && body.path("trackingNumbers").size() > 0
                        ? body.path("trackingNumbers").get(0).asText()
                        : null;
                String url = body.path("labelUrl").asText(null);

                return new CreationOutcome(id, tracking, allowedUrl(url) ? url : null, false);
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

        return guarded("labels", () -> {
            var response = OutboundDeadline.await(reads.get()
                    .uri(p.shipmentsPath() + "/" + id + "/label")
                    .exchangeToMono(r -> r.toEntity(byte[].class)), OutboundDeadline.after(p.readTimeout()));
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
            throw ShippingException.serviceUnavailable("PROVIDER_CONTEXT_UNAVAILABLE");
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

    private ResponseEntity<byte[]> post(WebClient client, String path, Object body, long deadline) {
        var response = OutboundDeadline.await(client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(r -> r.toEntity(byte[].class)), deadline);
        if (response == null || response.getBody() == null) {
            throw protocol();
        }
        return response;
    }

    private <T> T guarded(String operation, Supplier<T> work) {
        return operations.get(operation).execute(work);
    }

    private static ShippingException protocol() {
        return ShippingException.badGateway("PROVIDER_INVALID_RESPONSE");
    }

    private static ShippingException readFailure(int status) {
        return ShippingException.badGateway(
                status == 401 || status == 403 ? "PROVIDER_AUTHENTICATION_FAILED" : "PROVIDER_UNAVAILABLE"
        );
    }

    /**
     * Parses and validates the returned shipment ID from a JSON body.
     * Prevents returning a fallback '0' ID as a valid transaction identifier.
     */
    public void resetCircuitBreakers() {
        operations.values().forEach(OutboundOperation::resetCircuitBreaker);
    }

    @PreDestroy
    public void close() {
        operations.values().forEach(OutboundOperation::close);
    }
}