package com.enterprise.shipping.quote.infrastructure.provider;

import com.enterprise.shipping.quote.domain.*;
import com.enterprise.shipping.quote.port.CarrierRatePort;
import com.enterprise.shipping.quote.port.QuoteCommand;
import com.enterprise.shipping.shared.domain.ShippingMarketPolicy;
import com.enterprise.shipping.shared.infrastructure.http.OutboundAdmissionRejectedException;
import com.enterprise.shipping.shared.infrastructure.http.OutboundDeadline;
import com.enterprise.shipping.shared.infrastructure.http.OutboundGuard;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Same outbound policy as the provider client; fan-out adds scheduling and global deadlines only. */
public final class ResilientCarrierRateAdapter implements CarrierRatePort {
    private final CarrierRatePort delegate;
    private final OutboundGuard guard;
    private final ShippingMarketPolicy market;

    public ResilientCarrierRateAdapter(
            CarrierRatePort delegate, OutboundGuard guard, ShippingMarketPolicy market) {
        this.delegate = delegate;
        this.guard = guard;
        this.market = market;
    }

    @Override
    public String getCarrierId() {
        return delegate.getCarrierId();
    }

    @Override
    public String getCarrierName() {
        return delegate.getCarrierName();
    }

    @Override
    public long getMaxSubTaskTimeoutMs() {
        return delegate.getMaxSubTaskTimeoutMs();
    }

    @Override
    public List<CarrierQuote> fetchQuotes(QuoteCommand request, long timeoutMs) {
        try {
            return guard.call(
                    "default",
                    "rates",
                    OutboundDeadline.after(Duration.ofMillis(timeoutMs)),
                    deadline -> {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) {
                            throw new CarrierFailureException(OutcomeCode.TIMEOUT);
                        }
                        var quotes =
                                delegate.fetchQuotes(
                                        request,
                                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                        if (quotes == null
                                || quotes.stream()
                                        .anyMatch(
                                                q ->
                                                        q == null
                                                                || !getCarrierId()
                                                                        .equals(q.carrierId())
                                                                || q.rate() == null
                                                                || q.rate().signum() < 0
                                                                || !market.comparisonCurrency()
                                                                        .equals(q.currency())
                                                                || q.serviceLevel() == null
                                                                || q.serviceLevel().isBlank()
                                                                || q.estimatedDeliveryDays() < 0)) {
                            throw new CarrierFailureException(OutcomeCode.INVALID_RESPONSE);
                        }
                        if (System.nanoTime() > deadline) {
                            throw new CarrierFailureException(OutcomeCode.TIMEOUT);
                        }
                        return List.copyOf(quotes);
                    });
        } catch (OutboundAdmissionRejectedException rejected) {
            throw new CarrierFailureException(
                    rejected.reason() == OutboundAdmissionRejectedException.Reason.CIRCUIT_OPEN
                            ? OutcomeCode.CIRCUIT_OPEN
                            : OutcomeCode.CAPACITY_REJECTED);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (failure instanceof CarrierFailureException carrier) {
                throw carrier;
            }
            throw new CarrierFailureException(CarrierFailureException.classify(failure), failure);
        }
    }
}
