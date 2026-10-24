package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.api.WorkflowException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/** Shared guard ordering. Each operation owns its guards; admission is outside the breaker.
 * No automatic application retry is introduced, particularly for financial writes.
 */
public final class OutboundCallExecutor {
    private OutboundCallExecutor() {}

    public static <T> T call(Bulkhead bulkhead, CircuitBreaker breaker, Callable<T> work) throws Exception {
        return bulkhead.executeCallable(() -> breaker.executeCallable(work));
    }

    public static <T> T execute(Bulkhead bulkhead, CircuitBreaker breaker, Supplier<T> work) {
        try {
            return call(bulkhead, breaker, work::get);
        } catch (BulkheadFullException | CallNotPermittedException unavailable) {
            throw new WorkflowException("PROVIDER_CAPACITY_EXCEEDED", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (WorkflowException failure) {
            throw failure;
        } catch (Exception failure) {
            boolean timeout = false;
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t instanceof TimeoutException || t instanceof io.netty.handler.timeout.ReadTimeoutException) timeout = true;
            }
            throw new WorkflowException(timeout ? "PROVIDER_TIMEOUT" : "PROVIDER_UNAVAILABLE",
                    timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY);
        }
    }
}
