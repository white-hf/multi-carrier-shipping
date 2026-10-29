package com.enterprise.shipping.shared.infrastructure.http;

import java.util.Map;
import org.springframework.http.ResponseEntity;

/** Transport is available only inside a guarded call, with one absolute deadline. */
public interface OutboundClient {
    @FunctionalInterface
    interface Work<T> {
        T run(Exchange exchange) throws Exception;
    }

    interface Exchange {
        ResponseEntity<byte[]> get(String path);

        ResponseEntity<byte[]> post(String path, Object body);
    }

    default void register(OutboundEndpoint<?, ?> endpoint) {
        throw new UnsupportedOperationException(
                "Endpoint registration requires a registered OutboundClient");
    }

    default <Req, Resp> Resp invoke(
            OutboundEndpoint<Req, Resp> endpoint,
            Req request,
            long deadline) {
        return invoke(endpoint, Map.of(), request, deadline);
    }

    default <Req, Resp> Resp invoke(
            OutboundEndpoint<Req, Resp> endpoint,
            Map<String, ?> uriVariables,
            Req request,
            long deadline) {
        throw new UnsupportedOperationException(
                "Declarative endpoint invocation requires a registered OutboundClient");
    }

    default <T> T execute(String operation, long deadline, Work<T> work) {
        return execute("default", operation, deadline, work);
    }

    <T> T execute(
            String group,
            String operation,
            long deadline,
            Work<T> work);
}
