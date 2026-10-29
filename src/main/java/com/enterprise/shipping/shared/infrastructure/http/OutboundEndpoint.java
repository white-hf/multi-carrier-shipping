package com.enterprise.shipping.shared.infrastructure.http;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Objects;
import org.springframework.http.HttpMethod;

/**
 * Immutable declarative contract for an outbound HTTP operation.
 * Validated at registration time so invalid paths or duplicate operation names fail fast.
 */
public final class OutboundEndpoint<Req, Resp> {

    private final String operation;
    private final HttpMethod method;
    private final String pathTemplate;
    private final String group;
    private final StatusPolicy<Resp> statusPolicy;
    private final ResponseDecoder<Resp> decoder;

    private OutboundEndpoint(
            String operation,
            HttpMethod method,
            String pathTemplate,
            String group,
            StatusPolicy<Resp> statusPolicy,
            ResponseDecoder<Resp> decoder) {
        if (operation == null || !operation.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid outbound operation name: " + operation);
        }
        if (group == null || !group.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid outbound group name: " + group);
        }
        validatePathTemplate(pathTemplate);
        this.operation = operation;
        this.method = Objects.requireNonNull(method, "method");
        this.pathTemplate = pathTemplate;
        this.group = group;
        this.statusPolicy = Objects.requireNonNull(statusPolicy, "statusPolicy");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    public static <Req, Resp> OutboundEndpoint<Req, Resp> get(
            String operation, String pathTemplate, Class<Resp> responseType) {
        return new OutboundEndpoint<>(
                operation,
                HttpMethod.GET,
                pathTemplate,
                "default",
                StatusPolicy.of(200),
                ResponseDecoder.json(responseType));
    }

    public static <Req, Resp> OutboundEndpoint<Req, Resp> get(
            String operation, String pathTemplate, TypeReference<Resp> responseType) {
        return new OutboundEndpoint<>(
                operation,
                HttpMethod.GET,
                pathTemplate,
                "default",
                StatusPolicy.of(200),
                ResponseDecoder.json(responseType));
    }

    public static <Req, Resp> OutboundEndpoint<Req, Resp> post(
            String operation, String pathTemplate, Class<Resp> responseType) {
        return new OutboundEndpoint<>(
                operation,
                HttpMethod.POST,
                pathTemplate,
                "default",
                StatusPolicy.of(200),
                ResponseDecoder.json(responseType));
    }

    public static <Req, Resp> OutboundEndpoint<Req, Resp> post(
            String operation,
            String pathTemplate,
            Class<Req> requestType,
            Class<Resp> responseType) {
        Objects.requireNonNull(requestType, "requestType");
        return post(operation, pathTemplate, responseType);
    }

    public static <Req, Resp> OutboundEndpoint<Req, Resp> post(
            String operation, String pathTemplate, TypeReference<Resp> responseType) {
        return new OutboundEndpoint<>(
                operation,
                HttpMethod.POST,
                pathTemplate,
                "default",
                StatusPolicy.of(200),
                ResponseDecoder.json(responseType));
    }

    public OutboundEndpoint<Req, Resp> group(String groupName) {
        return new OutboundEndpoint<>(
                operation, method, pathTemplate, groupName, statusPolicy, decoder);
    }

    public OutboundEndpoint<Req, Resp> statusPolicy(StatusPolicy<Resp> policy) {
        return new OutboundEndpoint<>(
                operation, method, pathTemplate, group, policy, decoder);
    }

    public OutboundEndpoint<Req, Resp> decoder(ResponseDecoder<Resp> customDecoder) {
        return new OutboundEndpoint<>(
                operation, method, pathTemplate, group, statusPolicy, customDecoder);
    }

    public String operation() {
        return operation;
    }

    public HttpMethod method() {
        return method;
    }

    public String pathTemplate() {
        return pathTemplate;
    }

    public String group() {
        return group;
    }

    public StatusPolicy<Resp> statusPolicy() {
        return statusPolicy;
    }

    public ResponseDecoder<Resp> decoder() {
        return decoder;
    }

    static void validatePathTemplate(String path) {
        if (path == null
                || !path.startsWith("/")
                || path.startsWith("//")
                || path.contains("\\")
                || path.contains("..")
                || path.contains("?")
                || path.contains("#")) {
            throw new IllegalArgumentException(
                    "Outbound path template must stay on the configured origin: " + path);
        }
    }
}
