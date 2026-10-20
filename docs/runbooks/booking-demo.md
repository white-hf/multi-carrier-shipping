# Shipment booking demo

## Start

```sh
./mvnw test
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

No database server or Docker is needed. The first run creates `data/bookings.db` and `data/demo-carrier.db`. Retain both across restarts. The application and management endpoints remain loopback-only. The fixed local merchant is `demo-merchant`; this is not production authentication.

## Create and replay

```sh
curl -i http://127.0.0.1:8080/api/shipping/bookings \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: booking-example-001' \
  -d '{"orderReference":"order-001","carrierId":"fedex","serviceLevel":"Priority","parcel":{"originPostalCode":"L6K 3V3","destinationPostalCode":"M5V 2T6","weightKg":1,"lengthCm":10,"widthCm":10,"heightCm":10},"demoScenario":"NORMAL"}'
```

Expected: **201 / CONFIRMED**, stable booking ID and customer reference, carrier booking ID, tracking number, and a URL for a simulated text label. Repeat the same request/key to replay the same terminal response. Change `orderReference` under the same key to get **409 / IDEMPOTENCY_CONFLICT**. Use a new key for an intentionally different booking.

The `Location` header identifies the operation:

```sh
curl -sS http://127.0.0.1:8080/api/shipping/bookings/BOOKING_ID
```

## Response loss and reconciliation

Send the create request with a new key and `"demoScenario":"TIMEOUT_AFTER_ACCEPT"`. The simulated carrier commits its booking to the independent carrier database, then delays the HTTP response beyond the deadline.

Expected: **202 / PENDING_INQUIRY** with `Location` and `Retry-After`. Repeating the original request does not submit again. Poll the Location URL; the scheduled worker should discover the existing carrier receipt and return **CONFIRMED**. Responses may already be confirmed if reconciliation runs before a later retry is observed.

For a controlled restart demonstration, start with the scheduler disabled:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--shipping.booking.reconciliation-enabled=false"
```

Create the timeout-after-accept operation, stop the app after its 202 response, then restart normally using the same two database files. Inquiry recovers the existing shipment without another submission. JSON example outcomes are recorded in the validation summary after smoke checks.

## Other scenarios

| Scenario | Expected behavior |
| --- | --- |
| `NORMAL` | 201, confirmed text-label simulation |
| `REJECTED` | 422, terminal FAILED; repeated request replays it |
| `TIMEOUT_AFTER_ACCEPT` | 202, then confirmed by inquiry |
| `UNAVAILABLE` | 202, inquiry backoff; eventually MANUAL_REVIEW, never automatic resubmission |
| Missing `Idempotency-Key` header | 400 / INVALID_REQUEST; rejected during HTTP binding |
| Blank/malformed key | 400 / INVALID_IDEMPOTENCY_KEY; no claim created |
| Invalid parcel | 400 / INVALID_REQUEST; no claim created |
| Reused key with changed intent | 409; existing operation unchanged |
| SQLite unavailable/busy beyond timeout | 503; retry with the same key, since a carrier booking may already exist |

## Configuration and monitoring

`BOOKING_DB_PATH`, `DEMO_CARRIER_DB_PATH`, and `BOOKING_CARRIER_URL` override paths/URL. If the app port changes, update the simulated carrier URL too. Recovery cadence, leases, maximum attempts, and database limits are under `shipping.booking` (see `BookingProperties`). Scheduler execution is sequential and batches are bounded.

```sh
curl -sS http://127.0.0.1:9091/actuator/prometheus
```

Look for `shipping_booking_claims`, `shipping_booking_conflicts`, `shipping_booking_outcomes`, and `shipping_booking_latency` metric families. Use booking IDs in logs to follow claim, replay, uncertain submission, inquiry, and confirmation.

No real carrier API, payment, or postage is involved. Database files store submitted parcel data locally; use fictional inputs. No production throughput claim is made for SQLite.
