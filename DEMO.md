# Demo Guide

Run the simulated rate and booking workflows, reproduce failures, and inspect recovery. All carrier data is synthetic; no real shipment or charge is created.

## Start

Requirements: JDK 17. The Maven wrapper downloads Maven and dependencies on first use.

```sh
./mvnw test
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Application: `http://127.0.0.1:8080`. Health and metrics: `http://127.0.0.1:9091/actuator/`. The demo profile uses a fixed local owner and simulated carriers; keep it bound to loopback.

## Compare rates

In another terminal:

```sh
curl -sS http://127.0.0.1:8080/api/shipping/quotes \
  -H 'Content-Type: application/json' \
  -d '{"originPostalCode":"L6K 3V3","destinationPostalCode":"M5V 2T6","weight":1,"weightUnit":"KG","dimensionUnit":"CM","length":10,"width":10,"height":10}'
```

Expect `200 / COMPLETE`, with quotes sorted by price and explicit `carrierOutcomes`. A successful carrier query with no available services is `NO_SERVICES`, not an outage.

Stop the application before restarting with a different failure mode:

```sh
# One slow carrier; healthy-carrier results survive: 200 / PARTIAL.
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--demo.quote-carriers.fedex-mode=slow"
```

```sh
# Both carriers fail: 502 / FAILED.
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--demo.quote-carriers.fedex-mode=error --demo.quote-carriers.ups-mode=error"
```

Quote simulator modes: `success`, `slow`, `error`, `empty`, `malformed`. If no carrier query succeeds and at least one times out, the response is `504`; all work rejected for capacity yields `503`. Invalid input yields `400`. Cancellation bounds local waiting/resources; it cannot prove that remote processing stopped.

## Create, replay and inspect a shipment

With the application running normally:

```sh
curl -i http://127.0.0.1:8080/api/shipping/bookings \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: booking-demo-001' \
  -d '{"orderReference":"order-001","carrierId":"fedex","serviceLevel":"Priority","parcel":{"originPostalCode":"L6K 3V3","destinationPostalCode":"M5V 2T6","weight":1,"weightUnit":"KG","dimensionUnit":"CM","length":10,"width":10,"height":10,"originCountryCode":"CA","destinationCountryCode":"CA"}}'
```

Expect `201 / CONFIRMED`, with a booking ID, carrier reference, simulated tracking number and label URL. The `Location` header identifies the status endpoint.

- Repeat the same owner/key/intent: the same booking is returned without another submission.
- Change `orderReference` under the same key: `409 / IDEMPOTENCY_CONFLICT`.
- Use a different key: this is a new operation, even if the order reference is unchanged.

Replace `<booking-id>` with the returned ID:

```sh
curl -sS 'http://127.0.0.1:8080/api/shipping/bookings/<booking-id>'
```

A known booking returns HTTP `200` for GET; inspect its business `status`. The returned `labelUrl` points to a simulated text label, not valid postage.

## Recover after a lost carrier response or restart

Stop the application, then start it with a response-loss scenario and scheduling paused:

```sh
DEMO_BOOKING_MODE=TIMEOUT_AFTER_ACCEPT ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--shipping.reconciliation.scheduling-enabled=false"
```

1. Repeat the booking POST with a **fresh key**, such as `booking-recovery-001`. Expect `202 / PENDING_INQUIRY`: the carrier saved the shipment but withheld its response. Record the booking ID.
2. Repeat that POST with the same key/intent. It remains the same operation; no resubmission occurs.
3. Stop the application. Restart normally using the same database files:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

4. GET the saved booking ID until its status becomes `CONFIRMED`. Recovery finds the carrier's existing receipt. Both local databases must survive the restart.

Discovery runs in pages of 100 with a 30-second fixed delay; execution checks up to 16 due tasks with a 1-second fixed delay. A small backlog is normally found in the next discovery pass; larger sweeps can take several passes. Overdue PENDING claims become discoverable after the submission deadline (default 15 seconds). Workers only inquire; absence never authorizes another submission. Unresolved inquiries use capped backoff and reach MANUAL_REVIEW after the configured attempt limit (default 8). Paused tasks require operator investigation; no operator-resolution endpoint is implemented.

| `DEMO_BOOKING_MODE` | New operation |
| --- | --- |
| `NORMAL` | `201 / CONFIRMED` |
| `REJECTED` | `422 / FAILED`; definitive rejection is replayed |
| `TIMEOUT_AFTER_ACCEPT` | `202 / PENDING_INQUIRY`, followed by inquiry-based recovery |
| `UNAVAILABLE` | `202`; inquiry remains unresolved and eventually requires manual review |

## Configuration and local data

[Demo configuration](src/main/resources/application-demo.yml) contains the market, currency, carrier allowlist and simulator settings. Failure modes are server-side configuration, never business request fields. Carrier names/payloads are simulation fixtures, not live carrier contracts.

Both request types require `weightUnit` (`KG`/`LB`) and `dimensionUnit` (`CM`/`IN`). Values normalize exactly to kg/cm before hashing and dispatch. Optional country codes resolve from the configured default. Postal validation checks shape, not deliverability. Missing/unknown units, invalid values and unknown JSON fields return `400`.

| Setting | Purpose |
| --- | --- |
| `BOOKING_DB_PATH` | Application SQLite file: booking state and recovery tasks |
| `DEMO_CARRIER_DB_PATH` | Separate simulated-carrier SQLite file |
| `DEMO_BOOKING_MODE` | Booking fault scenario |
| `BOOKING_CARRIER_URL`, `FEDEX_BASE_URL`, `UPS_BASE_URL` | Simulator URLs; update these when changing the application port |
| `shipping.booking.enabled-carriers` | Admission allowlist; disabling a carrier preserves existing-key replay and recovery |
| `shipping.reconciliation.scheduling-enabled` | Pause scheduled loops while keeping recovery components loaded |

Booking and reconciliation load unconditionally. Outside `demo`, supply market/carrier settings, submission and inquiry adapters, and a trusted authenticated principal. Missing principals receive `401`; no login/token-verification service is supplied.

The SQLite files and WAL/SHM files are excluded from Git. Keep them to demonstrate restart recovery. After a development schema change, use fresh paths; automatic migrations are not provided:

```sh
BOOKING_DB_PATH=./data/fresh-bookings.db DEMO_CARRIER_DB_PATH=./data/fresh-carrier.db \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

SQLite WAL files remain local; this deployment is not a multi-host database cluster. No tracking, billing, real postage, shard router or remote recovery transport is implemented.

## Inspect errors and metrics

Booking errors distinguish missing/invalid input (`400`), changed intent (`409`), definitive carrier rejection (`422`), and storage unavailability (`503`). On a storage error, retry with the **same idempotency key** because a shipment may already exist. Per-carrier quote outcomes distinguish timeout, capacity rejection, circuit rejection, invalid response and upstream failure.

```sh
curl -sS http://127.0.0.1:9091/actuator/health
curl -sS http://127.0.0.1:9091/actuator/prometheus
```

Inspect aggregate/carrier latency, worker and connection-pool capacity, booking outcomes, and reconciliation events. Logs correlate quote request IDs or booking/task IDs; raw keys and parcel payloads are excluded from application logs. Metrics use bounded labels, not per-request identifiers. Centralized logging, alerting and distributed tracing remain deployment extensions.

Return to the [project overview](README.md).
