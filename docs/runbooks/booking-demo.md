# Shipment booking demo

## Start

```sh
./mvnw test
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

The demo profile supplies a simulated HTTP carrier, a fixed local owner, and a separate carrier database. The core booking API, aggregate, and recovery worker use the same code outside this profile. Both local databases are created under `data/`; retain them across restarts.

## Create and replay

```sh
curl -i http://127.0.0.1:8080/api/shipping/bookings \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: booking-example-001' \
  -d '{"orderReference":"order-001","carrierId":"fedex","serviceLevel":"Priority","parcel":{"originPostalCode":"L6K 3V3","destinationPostalCode":"M5V 2T6","weight":1,"weightUnit":"KG","dimensionUnit":"CM","length":10,"width":10,"height":10,"originCountryCode":"CA","destinationCountryCode":"CA"}}'
```

Expected: **201 / CONFIRMED**, a booking ID, customer reference, simulated tracking number, and text-label URL. Repeat the same intent/key to reconstruct the same recorded result; change the order reference under the same key to get **409 / IDEMPOTENCY_CONFLICT**. `Location` identifies the GET status endpoint.

Country codes may be omitted, resolving to `shipping.market.default-country` before hashing. Country-specific postal rules and comparison currency are configured under `shipping.market`. Postal validation checks shape, not deliverability.

## Measurement units

Both quote input and the booking parcel require `weightUnit` (`KG` or `LB`) and `dimensionUnit` (`CM` or `IN`). For example: `"weight":2,"weightUnit":"LB","length":10,"width":8,"height":4,"dimensionUnit":"IN"`. All three dimensions use the declared dimension unit. Missing/unknown units or nonpositive values return 400.

Conversion uses exact decimal factors: 1 inch = 2.54 cm and 1 pound = 0.45359237 kg. Physical input limits are checked after conversion. Carrier ports and booking fingerprints use normalized kg/cm, so equivalent physical measurements replay the same intent.

## Server-side fault injection

Fault controls are configuration of the simulated carrier, never business request fields:

```sh
DEMO_BOOKING_MODE=TIMEOUT_AFTER_ACCEPT ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Create a booking with a new key using the same business payload. The carrier commits its own database, then delays its response beyond the deadline. Expected: **202 / PENDING_INQUIRY**, followed by confirmation through inquiry after discovery (normally within the next 30-second discovery interval for a small backlog). Replaying the request never submits again.

For a controlled restart, disable the scheduler for the first run:

```sh
DEMO_BOOKING_MODE=TIMEOUT_AFTER_ACCEPT ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--shipping.reconciliation.scheduling-enabled=false"
```

After receiving 202, stop and restart normally with the same files. Inquiry discovers the existing carrier receipt. Changing the server's failure mode does not change the booking fingerprint.

| Server mode | Behavior for a new booking |
| --- | --- |
| `NORMAL` | 201 / CONFIRMED |
| `REJECTED` | 422 / FAILED, terminal replay |
| `TIMEOUT_AFTER_ACCEPT` | 202, then confirmation by inquiry |
| `UNAVAILABLE` | 202, bounded inquiry, eventually MANUAL_REVIEW |

## Carrier configuration

```yaml
shipping:
  booking:
    enabled-carriers: [fedex, ups]
```

Change the list and restart to enable/disable new operations. IDs are normalized. Existing bookings remain replayable and recoverable when admission is disabled. Real carrier integrations implement `CarrierBookingPort`; the list alone does not implement their protocols.

## Runtime configuration and ownership

`BOOKING_DB_PATH` sets the application file. `DEMO_CARRIER_DB_PATH`, `BOOKING_CARRIER_URL`, and `DEMO_BOOKING_MODE` configure only the simulator. The booking table and recovery task table share the application file in this deployment. Its structured settings live under `demo.booking-carrier`. When changing the application port, also update the simulator URL and quote simulator base URLs.

Outside the demo profile, explicitly enable booking, configure its database/market/carriers, and supply a real carrier port implementation. `PrincipalBookingOwnerProvider` requires a principal established by a servlet container or security integration. It never trusts an owner field/header. No authentication login/token-verification system is supplied by this repository; missing principals receive 401. The demo's fixed merchant is selected only by its profile.

## Errors and development data

| Input/outcome | Response |
| --- | --- |
| Missing required header | 400 / INVALID_REQUEST |
| Blank or malformed key | 400 / INVALID_IDEMPOTENCY_KEY |
| Unsupported carrier | 400 / UNSUPPORTED_CARRIER |
| Invalid parcel, unsupported country, or unknown JSON field | 400 / INVALID_REQUEST |
| Changed intent under an existing key | 409 / IDEMPOTENCY_CONFLICT |
| Storage unavailable | 503; retry with the same key |

Do not send simulator fields; unknown JSON fields are rejected. This project is under development and supports only its current intent format. If local databases were created by an earlier model, start with fresh file paths instead of migrating historical records:

```sh
BOOKING_DB_PATH=./data/recovery-bookings.db DEMO_CARRIER_DB_PATH=./data/recovery-carrier.db \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

## Monitoring

Prometheus: `http://127.0.0.1:9091/actuator/prometheus`. Booking metrics cover claims, conflicts, outcomes, and latency; `shipping.reconciliation.*` metrics cover discovery, inquiry and delivery; logs use booking IDs rather than raw keys or parcel data. SQLite is file-backed with bounded write contention. No real charge or valid postage is created.

Recovery runtime settings and table ownership: [reconciliation design](../design/reconciliation.md). Use fresh application database paths after this schema change; existing files are not migrated or removed.
