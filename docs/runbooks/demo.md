# Java 17 demo

Requirements: JDK 17. The included Maven wrapper downloads Maven 3.9.8 and project dependencies on first use. The Java 17 build, all 38 tests, and packaged demo smoke checks have passed on this Mac.

```sh
./mvnw test
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

The application binds to `127.0.0.1:8080`; operational endpoints bind separately to `127.0.0.1:9091`. Mock endpoints exist only under the explicit `demo` profile. This is a local interview demo without authentication; do not expose it through a public proxy or override the bind address without adding access control and rate limiting.

```sh
curl -sS http://127.0.0.1:8080/api/shipping/quotes \
  -H 'Content-Type: application/json' \
  -d '{"originPostalCode":"L6K 3V3","destinationPostalCode":"M5V 2T6","weightKg":1,"lengthCm":10,"widthCm":10,"heightCm":10}'
```

Restart with deterministic carrier modes to demonstrate failure behavior:

```sh
# FedEx times out; UPS remains available.
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments="--shipping.mock.fedex-mode=slow"
# Both dependencies fail.
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo -Dspring-boot.run.arguments="--shipping.mock.fedex-mode=error --shipping.mock.ups-mode=error"
```

Modes: `success`, `slow`, `error`, `empty`, `malformed`. Carrier URLs, timeouts, and concurrency are configurable in application.yml. Loopback URLs and simplified JSON are simulation fixtures, not real FedEx or UPS contracts. When changing the application port, also change the mock base URLs.

## Response contract

- 200 COMPLETE: all carrier queries succeeded; `NO_SERVICES` is a valid successful query with no offers.
- 200 PARTIAL: at least one query succeeded and another failed; this can contain no quotes if successful queries returned no services.
- 502 FAILED: no carrier query succeeded.
- 504 FAILED: no query succeeded and at least one timed out.
- 503 FAILED: all carrier work was rejected for capacity.
- 400: invalid measurements, postal-code shape, or malformed JSON.

Each result includes a generated request ID and structured `carrierOutcomes`. Prices are sorted in CAD, then carrier ID and service name. Error responses never include raw upstream exception messages. The API intentionally replaces the old degraded-zero-price quote model; clients must use typed outcomes rather than `failedCarriers` strings.

## Monitoring

```sh
curl -sS http://127.0.0.1:9091/actuator/health
curl -sS http://127.0.0.1:9091/actuator/prometheus
```

Aggregate latency histograms support P99 calculation in Prometheus. Carrier outcomes distinguish timeout, circuit rejection, capacity rejection, invalid payload, and upstream error. Transport timings measure attempts separately from user-visible aggregation outcomes. Pool metrics and active-worker gauges help investigate saturation. Request IDs are logged and forwarded to mock carrier calls; they are not metric labels.

## Limits

One domestic Canadian parcel with explicit decimal kg/cm measurements and comparable CAD estimates. Postal validation checks shape, not deliverability. No account pricing, FX, tax calculation, billing, or tracking implementation. Shipment booking is a separate [SQLite demo](booking-demo.md). Server-side fixtures may continue sleeping after the HTTP caller disconnects; cancellation limits local work, not remote side effects. A misbehaving blocking adapter can ignore interruption, but its bounded workers prevent unbounded thread growth and it cannot occupy another carrier's pool.

The original Spring Boot 3.2.3 dependency baseline is retained. No vulnerability scan or production-readiness certification has been performed. Current fixes and test coverage are documented in the validation summary.
