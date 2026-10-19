# Java 17 implementation and validation status

## Changes implemented

- Java 17-compatible Spring MVC request handling; virtual threads removed.
- One bounded executor with zero queue per carrier; separate Reactor Netty connection pools and bounded acquisition waits.
- One monotonic deadline, no nested carrier tasks, cancellation of unfinished work, and executor shutdown.
- Structured complete/partial/failed responses and carrier outcomes; multiple service quotes with deterministic CAD ranking.
- Required positive decimal measurements, bounded inputs, normalized payload checks, and safe error responses.
- Local bulkhead rejection excluded from carrier-breaker accounting by decorator placement.
- Separate transport/aggregate metrics, aggregate histograms, pool and worker metrics, and request correlation.
- Deterministic mock modes behind an explicit demo profile; loopback application and management listeners.

## Verified on October 18, 2026

Environment: macOS Apple Silicon, Homebrew OpenJDK 17.0.19, Apache Maven 3.9.8. The official Maven wrapper is included and its `./mvnw -v` launch was verified.

- Java compilation: successful, release target 17.
- Tests: **14 passed, 0 failures, 0 errors** (6 engine tests, 8 HTTP integration tests).
- Executable Spring Boot jar: packaged successfully.
- Engine tests verified sorting/no-services, deadline interruption, carrier saturation isolation, invalid currency isolation, open breakers, and global deadlines.
- HTTP integration tests verified API outcomes, invalid input, partial and total failures, empty results, malformed payloads, actual HTTP timeout cleanup, and endpoint exposure/histograms.

Commands:

```sh
./mvnw package
java -jar target/multi-courier-aggregation-service-1.0.0.jar --spring.profiles.active=demo
```

The first test run exposed a test-context issue: Spring Boot disables external metrics export in tests by default. The integration test now explicitly enables observability and asserts the Prometheus endpoint and histogram buckets.

A packaged cold-start smoke test exposed client initialization overhead consuming the original 250/300 ms carrier budgets. HTTP infrastructure and the MVC dispatcher are now initialized during startup. Carrier budgets were not increased.

## Packaged demo smoke checks

Each scenario started a fresh process with the `demo` profile. Prometheus histogram availability was checked in each process. Responses are recorded in [demo-smoke-results.json](demo-smoke-results.json).

| Scenario | HTTP | Comparison status | Quotes | Observed engine time |
| --- | --- | --- | --- | --- |
| normal | 200 | COMPLETE | 3 | 238 ms |
| partial | 200 | PARTIAL | 1 | 256 ms |
| failure | 502 | FAILED | 0 | 239 ms |

These are individual local smoke observations, not benchmarks or measured P99. All smoke processes were stopped after checks. The source includes explicit reactive timeout errors so carrier timeout telemetry is not mislabeled as a generic upstream failure; the integration test checks that distinction.

## Remaining limits

Carrier schemas are simulated. No production carrier contract, account pricing, FX/tax completeness, authentication, or production load testing is included. Default listeners are loopback-only. The original Spring Boot 3.2.3 dependency baseline is retained; no vulnerability scan was performed. Netty reports an optional native macOS DNS resolver fallback; local IP-based tests passed, but production DNS configuration requires separate validation.

GitHub publication and confirmed historical-project stories remain outside this validation result.
