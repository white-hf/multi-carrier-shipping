package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.PrematureCloseException;

/** Backend-only diagnostics. Error bodies are filtered, redacted and bounded; headers stay private. */
final class OutboundDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(OutboundDiagnostics.class);

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_ERROR_BYTES = 16 * 1024;
    private static final int MAX_LOG_CHARACTERS = 2048;
    private static final Set<String> ERROR_FIELDS =
            Set.of(
                    "error",
                    "errors",
                    "code",
                    "errorcode",
                    "message",
                    "messages",
                    "detail",
                    "description",
                    "title",
                    "status",
                    "field",
                    "parameter",
                    "reason",
                    "validationerrors");

    private OutboundDiagnostics() {
    }

    static void response(
            String dependency,
            String operation,
            int status,
            long started,
            String requestId,
            byte[] body,
            String bodyState) {
        if (bodyState == null
                && status == 404
                && ("rates".equals(operation) || "labels".equals(operation))) {
            return;
        }
        if (status < 200 || status >= 300) {
            log.warn(
                    "Outbound HTTP response dependency={} operation={} reason=HTTP_STATUS httpStatus={} elapsedMs={} requestId={} response={}",
                    dependency,
                    operation,
                    status,
                    elapsed(started),
                    requestId,
                    bodyState == null ? safeResponse(body) : bodyState);
        }
    }

    static void failure(
            String dependency,
            String group,
            String operation,
            Throwable failure,
            long started) {
        Throwable root = failure;
        Integer status = null;
        for (int depth = 0;
                root.getCause() != null && root.getCause() != root && depth < 16;
                depth++) {
            if (root instanceof WebClientResponseException response) {
                status = response.getStatusCode().value();
            }
            root = root.getCause();
        }
        if (root instanceof WebClientResponseException response) {
            status = response.getStatusCode().value();
        }
        // No status means this exception carries none, not that no HTTP response arrived.
        String statusDetail = status == null ? "" : " httpStatus=" + status;
        log.warn(
                "Outbound call failed dependency={} group={} operation={} reason={}{} exceptionType={} rootCauseType={} elapsedMs={}",
                dependency,
                group,
                operation,
                reason(failure),
                statusDetail,
                failure.getClass().getSimpleName(),
                root.getClass().getSimpleName(),
                elapsed(started));
    }

    static String safeResponse(byte[] body) {
        if (body == null || body.length == 0) {
            return "[EMPTY_BODY]";
        }
        if (body.length > MAX_ERROR_BYTES) {
            return "[OMITTED_OVERSIZED_BODY]";
        }
        try {
            String safe = JSON.writeValueAsString(filter(JSON.readTree(body), 0));
            return safe.length() <= MAX_LOG_CHARACTERS
                    ? safe
                    : safe.substring(0, MAX_LOG_CHARACTERS) + "[TRUNCATED]";
        } catch (Exception unreadable) {
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8).strip();
            if (text.startsWith("<")
                    || text.startsWith("{")
                    || text.startsWith("[")
                    || text.chars()
                            .anyMatch(
                                    value ->
                                            Character.isISOControl(value)
                                                    && value != '\n'
                                                    && value != '\r'
                                                    && value != '\t')) {
                return "[OMITTED_NON_JSON_BODY]";
            }
            // Plain-text validation errors use the same redaction and length limits as JSON
            // messages.
            return filter(JSON.getNodeFactory().textNode(text), 0).asText();
        }
    }

    private static JsonNode filter(JsonNode node, int depth) {
        if (node == null || depth > 8) {
            return JSON.getNodeFactory().textNode("[OMITTED]");
        }
        if (node.isObject()) {
            ObjectNode safe = JSON.createObjectNode();
            node.fields()
                    .forEachRemaining(
                            entry -> {
                                String key =
                                        entry.getKey().replace("_", "").toLowerCase(Locale.ROOT);
                                if (ERROR_FIELDS.contains(key)) {
                                    safe.set(entry.getKey(), filter(entry.getValue(), depth + 1));
                                } else {
                                    // Do not retain arbitrary keys: a provider may use customer
                                    // values as keys.
                                    safe.put("otherFields", "[REDACTED]");
                                }
                            });
            return safe;
        }
        if (node.isArray()) {
            var safe = JSON.createArrayNode();
            for (int i = 0; i < Math.min(node.size(), 10); i++) {
                safe.add(filter(node.get(i), depth + 1));
            }
            return safe;
        }
        if (node.isTextual()) {
            String text =
                    node.asText()
                            .replaceAll("(?i)(bearer|basic)\\s+[a-z0-9._~+/=-]+", "$1 [REDACTED]")
                            .replaceAll(
                                    "(?i)(password|secret|token|authorization|api[_-]?key)\\W+[^\\s,;]+",
                                    "$1=[REDACTED]")
                            .replaceAll(
                                    "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]")
                            .replaceAll("https?://[^\\s]+", "[URL]")
                            .replaceAll("\\+?\\d[\\d ()-]{8,}\\d", "[PHONE]")
                            .replaceAll("(?i)\\b[A-Z]\\d[A-Z][ -]?\\d[A-Z]\\d\\b", "[POSTAL_CODE]")
                            .replaceAll(
                                    "(?i)\\b\\d{1,6}\\s+[a-z0-9 .'-]{1,64}\\s(?:street|st|road|rd|avenue|ave|blvd|drive|dr|lane|ln)\\b",
                                    "[ADDRESS]")
                            .replaceAll("[\\r\\n\\t]", " ");
            return JSON.getNodeFactory()
                    .textNode(text.length() > 512 ? text.substring(0, 512) + "[TRUNCATED]" : text);
        }
        return node;
    }

    static String reason(Throwable failure) {
        Throwable cause = failure;
        String fallback = "UNCLASSIFIED_FAILURE";
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof OutboundAdmissionRejectedException rejected) {
                return rejected.reason() == OutboundAdmissionRejectedException.Reason.CIRCUIT_OPEN
                        ? "CIRCUIT_OPEN"
                        : "CAPACITY_REJECTED";
            }
            if (cause instanceof ConnectTimeoutException) {
                return "CONNECT_TIMEOUT";
            }
            if (cause instanceof ReadTimeoutException) {
                return "RESPONSE_TIMEOUT";
            }
            if (cause instanceof java.net.SocketTimeoutException) {
                // A generic socket timeout does not identify connect vs response phase.
                return "TRANSPORT_TIMEOUT";
            }
            if (cause instanceof UnknownHostException) {
                return "DNS_FAILED";
            }
            if (cause instanceof SSLException) {
                return "TLS_FAILED";
            }
            if (cause instanceof ConnectException) {
                return "CONNECT_FAILED";
            }
            if (cause instanceof PrematureCloseException) {
                return "CONNECTION_CLOSED";
            }
            if (cause instanceof DataBufferLimitException) {
                return "RESPONSE_TOO_LARGE";
            }
            if (cause instanceof WebClientResponseException) {
                return "HTTP_ERROR";
            }
            // Reactor shades its pool exceptions; avoid coupling to that internal package.
            if (cause.getClass().getSimpleName().equals("PoolAcquireTimeoutException")) {
                return "CONNECTION_ACQUIRE_TIMEOUT";
            }
            if (cause instanceof TimeoutException) {
                return "DEADLINE_EXCEEDED";
            }
            if (cause instanceof InterruptedException) {
                return "INTERRUPTED";
            }
            if (cause instanceof ShippingException shipping) {
                fallback =
                        shipping.code().equals("PROVIDER_INVALID_RESPONSE")
                                ? "INVALID_RESPONSE"
                                : shipping.category()
                                                == ShippingException.Category.DEPENDENCY_TIMEOUT
                                        ? "DEADLINE_EXCEEDED"
                                        : shipping.code();
            }
        }
        return fallback;
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
