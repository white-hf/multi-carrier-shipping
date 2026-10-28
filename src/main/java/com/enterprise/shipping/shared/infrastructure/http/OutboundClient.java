package com.enterprise.shipping.shared.infrastructure.http;

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

    default <T> T execute(String operation, long deadline, Work<T> work) {
        return execute("default", operation, deadline, work);
    }

    <T> T execute(
            String group,
            String operation,
            long deadline,
            Work<T> work);
}
