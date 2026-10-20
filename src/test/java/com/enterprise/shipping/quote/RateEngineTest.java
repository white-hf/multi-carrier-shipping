package com.enterprise.shipping.quote;

import com.enterprise.shipping.quote.infrastructure.monitoring.MicrometerQuoteObserver;
import com.enterprise.shipping.shared.domain.ShipmentParcel;
import com.enterprise.shipping.quote.port.CarrierRatePort;
import com.enterprise.shipping.quote.config.QuoteProperties;
import com.enterprise.shipping.quote.domain.*;
import com.enterprise.shipping.quote.application.HighFanOutAggregationEngine;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class RateEngineTest {
    static final QuoteCommand REQUEST = new QuoteCommand(new ShipmentParcel("L6K 3V3", "M5V 2T6", BigDecimal.ONE,
            BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, "CA", "CA"));
    interface QuoteCall { List<CarrierQuote> call(long timeout) throws Exception; }
    static CarrierRatePort adapter(String id, long timeout, QuoteCall call) {
        return new CarrierRatePort() {
            public String getCarrierId() { return id; }
            public String getCarrierName() { return id; }
            public long getMaxSubTaskTimeoutMs() { return timeout; }
            public List<CarrierQuote> fetchQuotes(QuoteCommand r, long budget) {
                try { return call.call(budget); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }
        };
    }
    static CarrierQuote quote(String id, String price) {
        return new CarrierQuote(id, id, "Ground", new BigDecimal(price), "CAD", 2);
    }
    static QuoteProperties properties(String... ids) {
        var properties = new QuoteProperties();
        for (String id : ids) {
            var config = new QuoteProperties.Carrier(); config.setConcurrency(id.equals("slow") ? 1 : 4);
            properties.getCarriers().put(id, config);
        }
        return properties;
    }
    static HighFanOutAggregationEngine engine(List<CarrierRatePort> adapters) {
        return new HighFanOutAggregationEngine(adapters, CircuitBreakerRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults(), new MicrometerQuoteObserver(new SimpleMeterRegistry()),
                properties(adapters.stream().map(CarrierRatePort::getCarrierId).toArray(String[]::new)), com.enterprise.shipping.support.TestMarket.canadian());
    }
    @Test void brokenMetricsCannotFailComparisonOrWorkerCreation() {
        var registry = org.mockito.Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
        // Unstubbed registry calls return null; the observer must isolate recording failures.
        var observer = new MicrometerQuoteObserver(registry);
        var engine = new HighFanOutAggregationEngine(
                List.of(adapter("good", 1000, t -> List.of(quote("good", "10")))),
                CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults(), observer, properties("good"), com.enterprise.shipping.support.TestMarket.canadian());
        try {
            var result = engine.executeFanOutRates(REQUEST, 1000);
            assertThat(result.status()).isEqualTo(ComparisonStatus.COMPLETE);
            assertThat(result.successfulQuotes()).hasSize(1);
        } finally { engine.close(); }
    }

    @Test void comparisonCurrencyComesFromMarketPolicy() {
        var market = new com.enterprise.shipping.shared.domain.ShippingMarketPolicy("CA", "USD",
                java.util.Map.of("CA", "[A-Z]\\d[A-Z]\\d[A-Z]\\d"));
        var engine = new HighFanOutAggregationEngine(List.of(adapter("usd", 1000,
                t -> List.of(new CarrierQuote("usd", "USD carrier", "Ground", BigDecimal.ONE, "USD", 1)))),
                CircuitBreakerRegistry.ofDefaults(), BulkheadRegistry.ofDefaults(),
                new MicrometerQuoteObserver(new SimpleMeterRegistry()), properties("usd"), market);
        try {
            var result = engine.executeFanOutRates(REQUEST, 1000);
            assertThat(result.status()).isEqualTo(ComparisonStatus.COMPLETE);
            assertThat(result.successfulQuotes()).extracting(CarrierQuote::currency).containsExactly("USD");
        } finally { engine.close(); }
    }

    @Test void sortsQuotesAndDistinguishesNoServices() {
        var engine = engine(List.of(adapter("b", 1000, t -> List.of(quote("b", "20"), quote("b", "10"))),
                adapter("a", 1000, t -> List.of(quote("a", "10"))), adapter("empty", 1000, t -> List.of())));
        try {
            var response = engine.executeFanOutRates(REQUEST, 1000);
            assertThat(response.status()).isEqualTo(ComparisonStatus.COMPLETE);
            assertThat(response.successfulQuotes()).extracting(CarrierQuote::carrierId).containsExactly("a", "b", "b");
            assertThat(response.carrierOutcomes()).contains(new CarrierOutcome("empty", OutcomeCode.NO_SERVICES));
        } finally { engine.close(); }
    }
    @Test void deadlineCancelsSlowWorkerAndPreservesFastQuotes() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        var engine = engine(List.of(adapter("fast", 1000, t -> List.of(quote("fast", "10"))),
                adapter("slow", 100, t -> { try { Thread.sleep(5000); } catch (InterruptedException ex) { interrupted.countDown(); throw ex; } return List.of(); })));
        try {
            long start = System.nanoTime();
            var response = engine.executeFanOutRates(REQUEST, 800);
            assertThat(response.status()).isEqualTo(ComparisonStatus.PARTIAL);
            assertThat(response.successfulQuotes()).hasSize(1);
            assertThat(response.carrierOutcomes()).contains(new CarrierOutcome("slow", OutcomeCode.TIMEOUT));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(700);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { engine.close(); }
    }
    @Test void saturatedCarrierDoesNotExhaustHealthyCarrier() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        var engine = engine(List.of(adapter("slow", 3000, t -> { started.countDown(); release.await(); return List.of(); }),
                adapter("fast", 3000, t -> List.of(quote("fast", "10")))));
        ExecutorService callers = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = callers.submit(() -> engine.executeFanOutRates(REQUEST, 3000));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            var second = engine.executeFanOutRates(REQUEST, 500);
            assertThat(second.carrierOutcomes()).contains(new CarrierOutcome("slow", OutcomeCode.CAPACITY_REJECTED));
            assertThat(second.successfulQuotes()).extracting(CarrierQuote::carrierId).containsExactly("fast");
            release.countDown(); first.get(1, TimeUnit.SECONDS);
        } finally { release.countDown(); callers.shutdownNow(); engine.close(); }
    }
    @Test void rejectsMalformedAndMixedCurrencyQuotes() {
        var engine = engine(List.of(adapter("bad", 1000, t -> List.of(new CarrierQuote("bad", "bad", "Ground", BigDecimal.ONE, "USD", 1))),
                adapter("good", 1000, t -> List.of(quote("good", "10")))));
        try {
            var response = engine.executeFanOutRates(REQUEST, 1000);
            assertThat(response.status()).isEqualTo(ComparisonStatus.PARTIAL);
            assertThat(response.carrierOutcomes()).contains(new CarrierOutcome("bad", OutcomeCode.INVALID_RESPONSE));
        } finally { engine.close(); }
    }
    @Test void openBreakerSkipsAdapterAndReportsTypedOutcome() {
        AtomicBoolean called = new AtomicBoolean();
        var breakers = CircuitBreakerRegistry.ofDefaults();
        breakers.circuitBreaker("bad").transitionToOpenState();
        var engine = new HighFanOutAggregationEngine(List.of(adapter("bad", 1000, t -> { called.set(true); return List.of(); })),
                breakers, BulkheadRegistry.ofDefaults(), new MicrometerQuoteObserver(new SimpleMeterRegistry()), properties("bad"), com.enterprise.shipping.support.TestMarket.canadian());
        try {
            var response = engine.executeFanOutRates(REQUEST, 1000);
            assertThat(response.status()).isEqualTo(ComparisonStatus.FAILED);
            assertThat(response.carrierOutcomes()).containsExactly(new CarrierOutcome("bad", OutcomeCode.CIRCUIT_OPEN));
            assertThat(called).isFalse();
        } finally { engine.close(); }
    }
    @Test void globalDeadlineAppliesEvenWhenCarrierTimeoutIsLonger() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        var engine = engine(List.of(adapter("slow", 5000, t -> { try { Thread.sleep(5000); } catch (InterruptedException ex) { cancelled.countDown(); throw ex; } return List.of(); })));
        try {
            var result = engine.executeFanOutRates(REQUEST, 100);
            assertThat(result.status()).isEqualTo(ComparisonStatus.FAILED);
            assertThat(result.carrierOutcomes()).containsExactly(new CarrierOutcome("slow", OutcomeCode.TIMEOUT));
            assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { engine.close(); }
    }
}
