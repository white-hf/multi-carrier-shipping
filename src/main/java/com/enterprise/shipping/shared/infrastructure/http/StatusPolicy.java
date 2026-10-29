package com.enterprise.shipping.shared.infrastructure.http;

import com.enterprise.shipping.shared.domain.ShippingException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;

/**
 * Reusable HTTP status resolution mechanism. Endpoints explicitly declare which status codes
 * decode payloads, return expected non-error branches, map to domain outcomes, or throw.
 */
public final class StatusPolicy<Resp> {

    private final IntPredicate decodeStatuses;
    private final Map<Integer, Supplier<Resp>> expectedBranches;
    private final Map<Integer, Supplier<Resp>> handledErrorBranches;
    private final Map<Integer, Supplier<RuntimeException>> statusErrors;
    private final IntFunction<RuntimeException> unhandledStatusMapper;
    private final Function<RuntimeException, RuntimeException> decodeFailureMapper;

    private StatusPolicy(
            IntPredicate decodeStatuses,
            Map<Integer, Supplier<Resp>> expectedBranches,
            Map<Integer, Supplier<Resp>> handledErrorBranches,
            Map<Integer, Supplier<RuntimeException>> statusErrors,
            IntFunction<RuntimeException> unhandledStatusMapper,
            Function<RuntimeException, RuntimeException> decodeFailureMapper) {
        this.decodeStatuses = Objects.requireNonNull(decodeStatuses, "decodeStatuses");
        this.expectedBranches = Map.copyOf(expectedBranches);
        this.handledErrorBranches = Map.copyOf(handledErrorBranches);
        this.statusErrors = Map.copyOf(statusErrors);
        this.unhandledStatusMapper =
                Objects.requireNonNull(unhandledStatusMapper, "unhandledStatusMapper");
        this.decodeFailureMapper =
                Objects.requireNonNull(decodeFailureMapper, "decodeFailureMapper");
    }

    public static <Resp> StatusPolicy<Resp> of(int... successStatuses) {
        if (successStatuses == null || successStatuses.length == 0) {
            throw new IllegalArgumentException("At least one decode status is required");
        }
        var allowed = new java.util.HashSet<Integer>();
        for (int status : successStatuses) {
            allowed.add(status);
        }
        Set<Integer> immutable = Set.copyOf(allowed);
        return new StatusPolicy<>(
                immutable::contains,
                Map.of(),
                Map.of(),
                Map.of(),
                StatusPolicy::defaultUnhandledStatus,
                Function.identity());
    }

    public static <Resp> StatusPolicy<Resp> of2xx() {
        return new StatusPolicy<>(
                status -> status >= 200 && status < 300,
                Map.of(),
                Map.of(),
                Map.of(),
                StatusPolicy::defaultUnhandledStatus,
                Function.identity());
    }

    public StatusPolicy<Resp> onExpected(int status, Supplier<Resp> branchValue) {
        Objects.requireNonNull(branchValue, "branchValue");
        var updated = new LinkedHashMap<>(expectedBranches);
        updated.put(status, branchValue);
        return new StatusPolicy<>(
                decodeStatuses,
                updated,
                handledErrorBranches,
                statusErrors,
                unhandledStatusMapper,
                decodeFailureMapper);
    }

    public StatusPolicy<Resp> onHandledError(int status, Supplier<Resp> outcomeValue) {
        Objects.requireNonNull(outcomeValue, "outcomeValue");
        var updated = new LinkedHashMap<>(handledErrorBranches);
        updated.put(status, outcomeValue);
        return new StatusPolicy<>(
                decodeStatuses,
                expectedBranches,
                updated,
                statusErrors,
                unhandledStatusMapper,
                decodeFailureMapper);
    }

    public StatusPolicy<Resp> onError(int status, Supplier<RuntimeException> errorSupplier) {
        Objects.requireNonNull(errorSupplier, "errorSupplier");
        var updated = new LinkedHashMap<>(statusErrors);
        updated.put(status, errorSupplier);
        return new StatusPolicy<>(
                decodeStatuses,
                expectedBranches,
                handledErrorBranches,
                updated,
                unhandledStatusMapper,
                decodeFailureMapper);
    }

    public StatusPolicy<Resp> otherwiseThrow(IntFunction<RuntimeException> mapper) {
        return new StatusPolicy<>(
                decodeStatuses,
                expectedBranches,
                handledErrorBranches,
                statusErrors,
                mapper,
                decodeFailureMapper);
    }

    public StatusPolicy<Resp> onDecodeFailure(Function<RuntimeException, RuntimeException> mapper) {
        return new StatusPolicy<>(
                decodeStatuses,
                expectedBranches,
                handledErrorBranches,
                statusErrors,
                unhandledStatusMapper,
                mapper);
    }

    public OutboundDiagnostics.Classification classify(int status) {
        if (expectedBranches.containsKey(status)) {
            return OutboundDiagnostics.Classification.EXPECTED_BRANCH;
        }
        if (handledErrorBranches.containsKey(status) || statusErrors.containsKey(status)) {
            return OutboundDiagnostics.Classification.DEPENDENCY_FAILURE;
        }
        if (decodeStatuses.test(status)) {
            return OutboundDiagnostics.Classification.SUCCESS;
        }
        return OutboundDiagnostics.Classification.DEPENDENCY_FAILURE;
    }

    public Resp resolve(
            ResponseEntity<byte[]> response,
            ResponseDecoder<Resp> decoder,
            Runnable onProtocolError) {
        if (response == null) {
            onProtocolError.run();
            throw decodeFailureMapper.apply(
                    ShippingException.dependencyFailure("PROVIDER_INVALID_RESPONSE"));
        }
        int status = response.getStatusCode().value();

        // 1. Expected business branches (e.g. 404 empty result or 202 pending resource)
        if (expectedBranches.containsKey(status)) {
            return expectedBranches.get(status).get();
        }

        // 2. Handled error outcomes that return a domain value after diagnostic logging
        if (handledErrorBranches.containsKey(status)) {
            return handledErrorBranches.get(status).get();
        }

        // 3. Explicit per-status exception mappings
        if (statusErrors.containsKey(status)) {
            throw statusErrors.get(status).get();
        }

        // 4. Success statuses that require payload decoding and validation
        if (decodeStatuses.test(status)) {
            try {
                return decoder.decode(response);
            } catch (RuntimeException decodeFailure) {
                onProtocolError.run();
                throw decodeFailureMapper.apply(decodeFailure);
            }
        }

        // 5. Fallback for all other unmapped HTTP statuses
        throw unhandledStatusMapper.apply(status);
    }

    private static RuntimeException defaultUnhandledStatus(int status) {
        return ShippingException.dependencyFailure("PROVIDER_UNAVAILABLE");
    }
}
