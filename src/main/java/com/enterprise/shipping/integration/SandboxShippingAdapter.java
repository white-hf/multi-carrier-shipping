package com.enterprise.shipping.integration;

import com.enterprise.shipping.integration.config.ProviderProperties;
import com.enterprise.shipping.quote.port.RateProviderPort;
import com.enterprise.shipping.quote.domain.ShippingQuote;
import com.enterprise.shipping.booking.port.*;
import com.enterprise.shipping.shared.api.WorkflowException;
import com.enterprise.shipping.shared.domain.*;
import com.enterprise.shipping.shared.infrastructure.monitoring.MonitorCarrierApi;
import com.enterprise.shipping.shared.infrastructure.http.CarrierHttpClientFactory;
import com.fasterxml.jackson.databind.*;
import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.*;
import reactor.netty.resources.ConnectionProvider;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.*;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Handles Sandbox Shipping Provider API requests, payload parsing, and unit conversion.
 * 
 * Configures dedicated, isolated connection pools, bulkheads, and circuit breakers 
 * for each operation type (rates, creates, labels) to prevent downstream rate timeouts 
 * or failures from exhausting creation/booking resources.
 */
public class SandboxShippingAdapter implements RateProviderPort, ShipmentProviderPort, LabelProviderPort {

    private final ProviderProperties p;
    private final ObjectMapper mapper;
    private final Clock clock;

    private final WebClient rates;
    private final WebClient creates;
    private final WebClient reads;

    private final List<ConnectionProvider> pools = new ArrayList<>();
    private final Map<String, Bulkhead> bulkheads = new HashMap<>();
    private final Map<String, CircuitBreaker> breakers = new HashMap<>();

    public SandboxShippingAdapter(ProviderProperties p, ObjectMapper mapper, Clock clock) {
        this.p = p;
        this.mapper = mapper;
        this.clock = clock;

        this.rates = client("rates", p.readConcurrency(), p.rateTimeout());
        this.creates = client("create", p.createConcurrency(), p.createTimeout());
        this.reads = client("labels", p.readConcurrency(), p.readTimeout());
    }

    private WebClient client(String operation, int capacity, Duration timeout) {
        // Construct an isolated HTTP client pool to protect capacity
        var pool = CarrierHttpClientFactory.pool("provider-" + operation, capacity);
        pools.add(pool);

        // Limit concurrent downstream requests
        var bulkhead = Bulkhead.of("provider-" + operation, BulkheadConfig.custom()
                .maxConcurrentCalls(capacity)
                .maxWaitDuration(Duration.ZERO)
                .build());
        bulkheads.put(operation, bulkhead);

        // Circuit breaker protects against cascading network outages.
        // Client errors (4xx) are ignored from the failure count to prevent misbehaving inputs from tripping breakers.
        var circuitBreaker = CircuitBreaker.of("provider-" + operation, CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(5)
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .ignoreException(e -> e instanceof WorkflowException w && w.status().is4xxClientError())
                .build());
        breakers.put(operation, circuitBreaker);

        return CarrierHttpClientFactory.client(pool, p.baseUrl(), timeout.toMillis(), CarrierHttpClientFactory.TransportRetry.DISABLED)
                .mutate()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .defaultHeaders(h -> h.setBasicAuth(p.username(), p.password()))
                .build();
    }

    @Override
    @MonitorCarrierApi(carrier = "shipping-provider")
    public List<ShippingQuote> fetchRates(ShippingIntent intent, long deadline) {
        return guarded("rates", () -> {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new WorkflowException("PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT);
            }

            var response = post(rates, "/rest/rates", rateRequest(intent), Duration.ofNanos(remaining));
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw readFailure(response.getStatusCode().value());
            }

            try {
                var body = mapper.readTree(response.getBody());
                var available = body.get("availableRates");
                if (available == null || !available.isArray()) {
                    throw protocol();
                }

                List<ShippingQuote> result = new ArrayList<>();
                for (var q : available) {
                    var money = q.path("totalCharge");
                    if (!money.path("amount").isIntegralNumber() || !money.path("amount").canConvertToLong() || money.path("amount").asLong() < 0) {
                        throw protocol();
                    }

                    String currency = text(money, "currency");
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

                    result.add(new ShippingQuote(
                            null, null, p.providerId(), p.accountAlias(),
                            text(q, "quoteId"), text(q, "carrierId"), q.path("carrierName").asText(null),
                            text(q, "serviceId"), q.path("serviceName").asText(null),
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

    @Override
    @MonitorCarrierApi(carrier = "shipping-provider")
    public Outcome create(Submission submission) {
        requireContext(submission.providerId(), submission.accountAlias());
        String quote = submission.providerQuoteId();

        return guarded("create", () -> {
            // Quote-only mode: Adding reference or parcel payloads can alter billing and price guarantees.
            var response = post(creates, p.shipmentsPath(), Map.of("quoteId", quote), p.createTimeout());
            int status = response.getStatusCode().value();

            if (status == 400 || status == 401 || status == 403 || status == 422) {
                return Outcome.rejection();
            }
            if (status != 200 && status != 201) {
                throw new WorkflowException("SHIPMENT_OUTCOME_UNKNOWN", HttpStatus.ACCEPTED);
            }

            try {
                var body = mapper.readTree(response.getBody());
                String id = shipmentId(body);
                String tracking = body.path("trackingNumbers").isArray() && body.path("trackingNumbers").size() > 0
                        ? body.path("trackingNumbers").get(0).asText()
                        : null;
                String url = body.path("labelUrl").asText(null);

                return new Outcome(id, tracking, allowedUrl(url) ? url : null, false);
            } catch (java.io.IOException e) {
                throw protocol();
            }
        });
    }

    @Override
    @MonitorCarrierApi(carrier = "shipping-provider")
    public Label fetch(Identity identity) {
        requireContext(identity.providerId(), identity.accountAlias());
        String id = identity.shipmentId();
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw protocol();
        }

        return guarded("labels", () -> {
            var response = reads.get()
                    .uri(p.shipmentsPath() + "/" + id + "/label")
                    .exchangeToMono(r -> r.toEntity(byte[].class))
                    .block(p.readTimeout());
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
                return new Label(bytes, "application/pdf", null);
            }

            if (content != null && MediaType.APPLICATION_JSON.isCompatibleWith(content)) {
                try {
                    String url = mapper.readTree(bytes).path("labelUrl").asText(null);
                    if (allowedUrl(url)) {
                        return new Label(null, null, url);
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
            throw new WorkflowException("PROVIDER_CONTEXT_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Override
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

    private ResponseEntity<byte[]> post(WebClient client, String path, Object body, Duration timeout) {
        var response = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(r -> r.toEntity(byte[].class))
                .block(timeout);
        if (response == null || response.getBody() == null) {
            throw protocol();
        }
        return response;
    }

    private <T> T guarded(String operation, Supplier<T> work) {
        try {
            return bulkheads.get(operation).executeSupplier(() -> breakers.get(operation).executeSupplier(work));
        } catch (BulkheadFullException | CallNotPermittedException unavailable) {
            throw new WorkflowException("PROVIDER_CAPACITY_EXCEEDED", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (WorkflowException error) {
            throw error;
        } catch (RuntimeException failure) {
            boolean timeout = false;
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t instanceof TimeoutException || t instanceof io.netty.handler.timeout.ReadTimeoutException) {
                    timeout = true;
                }
            }
            throw new WorkflowException(
                    timeout ? "PROVIDER_TIMEOUT" : "PROVIDER_UNAVAILABLE",
                    timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY
            );
        }
    }

    private static WorkflowException protocol() {
        return new WorkflowException("PROVIDER_INVALID_RESPONSE", HttpStatus.BAD_GATEWAY);
    }

    private static WorkflowException readFailure(int status) {
        return new WorkflowException(
                status == 401 || status == 403 ? "PROVIDER_AUTHENTICATION_FAILED" : "PROVIDER_UNAVAILABLE",
                HttpStatus.BAD_GATEWAY
        );
    }

    /**
     * Parses and validates the returned shipment ID from a JSON body.
     * Prevents returning a fallback '0' ID as a valid transaction identifier.
     */
    private static String shipmentId(JsonNode body) {
        JsonNode id = body.get("shipId");
        if (id != null && id.isIntegralNumber() && id.canConvertToLong() && id.longValue() > 0) {
            return Long.toString(id.longValue());
        }
        if (id != null && id.isTextual() && id.textValue().matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.toString(Long.parseLong(id.textValue()));
            } catch (NumberFormatException invalid) {
                throw protocol();
            }
        }
        throw protocol();
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isValueNode() || value.asText().isBlank()) {
            throw protocol();
        }
        return value.asText();
    }

    private Map<String, Object> rateRequest(ShippingIntent intent) {
        var parcel = intent.parcel();
        boolean imperial = parcel.dimensionUnit() == DimensionUnit.IN;

        // Mixed input units are converted to one consistent provider system without floating-point errors
        var weight = imperial
                ? (parcel.weightUnit() == WeightUnit.LB ? parcel.weight() : parcel.weight().divide(new java.math.BigDecimal("0.45359237"), 12, java.math.RoundingMode.HALF_UP).stripTrailingZeros())
                : (parcel.weightUnit() == WeightUnit.KG ? parcel.weight() : parcel.weight().multiply(new java.math.BigDecimal("0.45359237")).stripTrailingZeros());

        return Map.of(
                "from", address(intent.sender()),
                "to", address(intent.recipient()),
                "packageType", parcel.packageType(),
                "lineItems", List.of(Map.of("length", parcel.length(), "width", parcel.width(), "height", parcel.height(), "weight", weight)),
                "unitOfMeasurement", imperial ? "IMPERIAL" : "METRIC",
                "shipDate", intent.shippingDate().toString(),
                "waitTimeLimit", p.collectionSeconds()
        );
    }

    private Map<String, Object> address(ShippingAddress a) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attention", a.contactName());
        result.put("streetAddress", a.addressLine1());
        result.put("city", a.city());
        result.put("state", a.province());
        result.put("postalCode", a.postalCode());
        result.put("countryCode", a.countryCode());
        result.put("phone", a.phone());
        result.put("residential", a.residential());

        if (a.companyName() != null) {
            result.put("companyName", a.companyName());
        }
        if (a.addressLine2() != null) {
            result.put("streetAddress2", a.addressLine2());
        }
        if (a.email() != null) {
            result.put("email", a.email());
        }
        return result;
    }

    public void resetCircuitBreakers() {
        breakers.values().forEach(CircuitBreaker::reset);
    }

    @PreDestroy
    public void close() {
        pools.forEach(ConnectionProvider::dispose);
    }
}