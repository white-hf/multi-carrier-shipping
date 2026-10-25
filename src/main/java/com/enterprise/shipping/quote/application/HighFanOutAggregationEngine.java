package com.enterprise.shipping.quote.application;

import com.enterprise.shipping.shared.domain.ShippingException;
import com.enterprise.shipping.shared.domain.ShippingMarketPolicy;
import com.enterprise.shipping.quote.port.CarrierRatePort;
import com.enterprise.shipping.quote.port.QuoteCommand;
import com.enterprise.shipping.quote.config.QuoteProperties;
import com.enterprise.shipping.quote.domain.*;
import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.*;
import com.enterprise.shipping.quote.port.QuoteObserver;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects independent carrier results against one request deadline on Java 17.
 * Each carrier owns bounded workers; a slow dependency cannot consume another's capacity.
 */
public class HighFanOutAggregationEngine implements com.enterprise.shipping.quote.port.RateComparisonPort {
    private static final long WORKER_IDLE_TIMEOUT_SECONDS = 30;

    private static final Logger log = LoggerFactory.getLogger(HighFanOutAggregationEngine.class);
    private final List<CarrierRatePort> adapters;
    private final Map<String, ThreadPoolExecutor> executors = new LinkedHashMap<>();
    private final CircuitBreakerRegistry breakers;
    private final BulkheadRegistry bulkheads;
    private final QuoteObserver observer;
    private final ShippingMarketPolicy market;
    private final Semaphore admission;

    public HighFanOutAggregationEngine(List<CarrierRatePort> adapters, CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads, QuoteObserver observer, QuoteProperties properties, ShippingMarketPolicy market) {
        if (adapters.isEmpty()) throw new IllegalArgumentException("At least one rate adapter must be configured");
        this.adapters = List.copyOf(adapters);
        this.breakers = breakers;
        this.bulkheads = bulkheads;
        this.observer = observer;
        this.market = market;
        this.admission = new Semaphore(properties.getMaxConcurrentRequests());
        for (CarrierRatePort adapter : adapters) {
            String id = adapter.getCarrierId();
            if (executors.containsKey(id)) throw new IllegalArgumentException("Duplicate carrier ID: " + id);
            int size = properties.carrier(id).getConcurrency();
            AtomicInteger sequence = new AtomicInteger();
            // A direct handoff queue rejects overload instead of accumulating stale requests.
            var executor = new ThreadPoolExecutor(size, size, WORKER_IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), task -> new Thread(task, "carrier-" + id + "-" + sequence.incrementAndGet()),
                    new ThreadPoolExecutor.AbortPolicy());
            executor.allowCoreThreadTimeOut(true);
            executors.put(id, executor);
            observer.workers(id, executor::getActiveCount);
        }
    }
    /** The legacy carrier comparison can share the result contract without inventing bookable provider tokens. */
    @Override public List<com.enterprise.shipping.quote.domain.ShippingQuote> compare(
            com.enterprise.shipping.shared.domain.ShippingIntent intent,long deadlineNanos) {
        var p=intent.parcel();
        var parcel=new com.enterprise.shipping.shared.domain.ShipmentParcel(intent.sender().postalCode(),intent.recipient().postalCode(),
                p.weightUnit().toKilograms(p.weight()),p.dimensionUnit().toCentimeters(p.length()),
                p.dimensionUnit().toCentimeters(p.width()),p.dimensionUnit().toCentimeters(p.height()),
                intent.sender().countryCode(),intent.recipient().countryCode());
        long remaining=Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadlineNanos-System.nanoTime()));
        var result=executeFanOutRates(new QuoteCommand(parcel),remaining);
        if(result.status()==ComparisonStatus.FAILED) throw ShippingException.badGateway("PROVIDER_UNAVAILABLE");
        return result.successfulQuotes().stream().map(q->new com.enterprise.shipping.quote.domain.ShippingQuote(null,null,
                "direct-carrier-fanout","carrier-account",null,q.carrierId(),q.carrierName(),q.serviceLevel(),q.serviceLevel(),
                q.rate().movePointRight(2).longValueExact(),q.currency(),q.estimatedDeliveryDays(),System.currentTimeMillis()+60000)).toList();
    }

    private record Result(String carrierId, OutcomeCode code, List<CarrierQuote> quotes) {}
    // Absolute monotonic time, never a wall-clock timestamp or a fresh timeout per wait.
    private record Pending(String carrierId, long deadline) {}

    /** Returns valid quotes plus explicit coverage; an empty successful query is not an outage. */
    public QuoteComparison executeFanOutRates(QuoteCommand request, long globalTimeoutMs) {
        market.validate(request.parcel());
        if (globalTimeoutMs <= 0) throw new IllegalArgumentException("Positive timeout required");
        String requestId = UUID.randomUUID().toString();
        // Monotonic time is unaffected by clock synchronization and manual clock changes.
        long start = System.nanoTime();
        long globalDeadline = start + TimeUnit.MILLISECONDS.toNanos(globalTimeoutMs);
        Map<Future<Result>, Pending> pending = new LinkedHashMap<>();
        BlockingQueue<Future<Result>> completed = new LinkedBlockingQueue<>();
        List<Result> results = new ArrayList<>();
        // Bound whole-request fan-out in addition to each carrier's outbound work.
        boolean admitted = admission.tryAcquire();
        try {
            for (CarrierRatePort adapter : adapters) {
                String id = adapter.getCarrierId();
                if (!admitted) { results.add(new Result(id, OutcomeCode.CAPACITY_REJECTED, List.of())); continue; }
                // The carrier budget includes scheduling, admission, and connection acquisition.
                long deadline = Math.min(globalDeadline,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(adapter.getMaxSubTaskTimeoutMs()));
                try {
                    var completion = new ExecutorCompletionService<Result>(executors.get(id), completed);
                    Future<Result> future = completion.submit(() -> invoke(adapter, request, deadline, requestId));
                    pending.put(future, new Pending(id, deadline));
                } catch (RejectedExecutionException ex) {
                    results.add(new Result(id, OutcomeCode.CAPACITY_REJECTED, List.of()));
                }
            }
            // Wake on any completion or the nearest deadline: sequential waits would multiply latency.
            while (!pending.isEmpty()) {
                long now = System.nanoTime();
                long nextDeadline = globalDeadline;
                var iterator = pending.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    Future<Result> future = entry.getKey();
                    Pending work = entry.getValue();
                    if (future.isDone()) {
                        try { results.add(future.get()); }
                        catch (ExecutionException | CancellationException ex) {
                            results.add(new Result(work.carrierId(), OutcomeCode.UPSTREAM_ERROR, List.of()));
                        }
                        iterator.remove();
                    } else if (now >= work.deadline()) {
                        // Cancellation is cooperative; adapters must honor interruption and transport timeouts.
                        future.cancel(true);
                        results.add(new Result(work.carrierId(), OutcomeCode.TIMEOUT, List.of()));
                        iterator.remove();
                    } else nextDeadline = Math.min(nextDeadline, work.deadline());
                }
                if (!pending.isEmpty()) completed.poll(Math.max(1, nextDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException ex) {
            // Preserve caller cancellation and stop all outstanding carrier work in finally.
            Thread.currentThread().interrupt();
            pending.forEach((future, work) -> results.add(new Result(work.carrierId(), OutcomeCode.INTERRUPTED, List.of())));
        } finally {
            pending.keySet().forEach(future -> future.cancel(true));
            if (admitted) admission.release();
        }
        // Quote validation enforces one configured currency before sorting, so numeric prices are comparable.
        var quotes = results.stream().flatMap(r -> r.quotes().stream())
                .sorted(Comparator.comparing(CarrierQuote::rate).thenComparing(CarrierQuote::carrierId)
                        .thenComparing(CarrierQuote::serviceLevel)).toList();
        // NO_SERVICES still means the dependency answered successfully.
        long successes = results.stream().filter(r -> r.code() == OutcomeCode.SUCCESS || r.code() == OutcomeCode.NO_SERVICES).count();
        ComparisonStatus status;
        if (successes == 0) {
            status = ComparisonStatus.FAILED;
        } else if (successes == adapters.size()) {
            status = ComparisonStatus.COMPLETE;
        } else {
            status = ComparisonStatus.PARTIAL;
        }
        results.forEach(r -> observer.carrierOutcome(r.carrierId(), r.code()));
        long elapsed = System.nanoTime() - start;
        observer.comparison(status, elapsed);
        log.info("Rate comparison requestId={} status={} quotes={} elapsedMs={}", requestId, status, quotes.size(), TimeUnit.NANOSECONDS.toMillis(elapsed));
        return new QuoteComparison(requestId, status, quotes,
                results.stream().sorted(Comparator.comparing(Result::carrierId))
                        .map(r -> new CarrierOutcome(r.carrierId(), r.code())).toList(), TimeUnit.NANOSECONDS.toMillis(elapsed));
    }

    private Result invoke(CarrierRatePort adapter, QuoteCommand request, long deadline, String requestId) {
        String id = adapter.getCarrierId();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            // Local admission failures must not count as remote carrier failures.
            List<CarrierQuote> quotes = com.enterprise.shipping.shared.infrastructure.http.OutboundCallExecutor.call(
                    bulkheads.bulkhead(id), breakers.circuitBreaker(id), () -> {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) throw new CarrierFailureException(OutcomeCode.TIMEOUT);
                        var value = adapter.fetchQuotes(request, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                        validateQuotes(id, value);
                        if (System.nanoTime() > deadline) throw new CarrierFailureException(OutcomeCode.TIMEOUT);
                        return value;
                    });
            return new Result(id, quotes.isEmpty() ? OutcomeCode.NO_SERVICES : OutcomeCode.SUCCESS, quotes);
        } catch (BulkheadFullException ex) { return new Result(id, OutcomeCode.CAPACITY_REJECTED, List.of()); }
        catch (CallNotPermittedException ex) { return new Result(id, OutcomeCode.CIRCUIT_OPEN, List.of()); }
        catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            OutcomeCode code = CarrierFailureException.classify(ex);
            log.warn("Carrier failure requestId={} carrier={} outcome={} type={}", requestId, id, code, ex.getClass().getSimpleName());
            return new Result(id, code, List.of());
        }
    }
    private void validateQuotes(String id, List<CarrierQuote> quotes) {
        if (quotes == null || quotes.stream().anyMatch(q -> q == null || !id.equals(q.carrierId()) ||
                q.rate() == null || q.rate().signum() < 0 || !market.comparisonCurrency().equals(q.currency()) ||
                q.serviceLevel() == null || q.serviceLevel().isBlank() || q.estimatedDeliveryDays() < 0))
            throw new CarrierFailureException(OutcomeCode.INVALID_RESPONSE);
    }
    /** Stop admission of new tasks and interrupt existing workers when the application shuts down. */
    @PreDestroy
    public void close() {
        executors.values().forEach(ThreadPoolExecutor::shutdownNow);
    }
}
