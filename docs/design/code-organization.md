# Code organization

One Maven project and one Spring Boot application, organized first by business feature.

```text
com.enterprise.shipping
├── quote/
│   ├── api/
│   ├── application/
│   ├── domain/
│   ├── port/
│   ├── infrastructure/{carrier,monitoring}/
│   └── config/
├── booking/
│   ├── api/
│   ├── application/
│   ├── domain/
│   ├── port/
│   ├── infrastructure/{carrier,persistence,monitoring}/
│   └── config/
├── shared/
│   ├── api/
│   ├── domain/
│   └── infrastructure/{http,monitoring}/
└── demo/{quote,booking}/
```

## Boundaries

Controllers map validated input to application commands. Application services coordinate workflows through ports. The booking aggregate and domain value types contain no Spring, HTTP-client, or database dependencies. Infrastructure implements carrier, storage, and observer ports; configuration wires dependencies and owns pool shutdown. Tests mirror the feature packages.

Neither feature imports the other. Shared code imports neither feature. Quote infrastructure has no persistence package because it currently needs no database.

## Reuse

| Component | Reused responsibility |
| --- | --- |
| `shared.api.ParcelRequest` | Validated parcel fields; quote input remains flat, booking input keeps its nested `parcel` |
| `shared.domain.ShipmentParcel` | Immutable normalized postal codes, dimensions, and weight |
| `shared.api.ApiResponse`, `CommonExceptionHandler` | Response envelope and generic safe HTTP errors |
| `CarrierHttpClientFactory` | Bounded pool construction, deadlines, response-size limits, warmup |
| Carrier monitoring annotation/aspect | Transport timings with failure-isolated recording |

The HTTP factory creates independent resources. FedEx quotes, UPS quotes, and booking have separate pools. Retry policy is explicit: the existing quote transport behavior is preserved, while booking disables automatic transport retries.

`CarrierRatePort` and `CarrierBookingPort` remain separate capability contracts. Quote deadlines/partial results and booking claims/reconciliation remain independent workflows. Booking canonical serialization and response snapshots stay local to booking.

`QuoteObserver` and `BookingObserver` separate application events from Micrometer. Their implementations preserve existing metric names and isolate recording failures. The quote worker gauge retains its supplier for the registry lifetime.

## Booking domain behavior

`ShipmentBooking` owns receipt validation and lifecycle transitions. Application services invoke its behavior instead of assigning statuses or constructing arbitrary outcomes. `BookingRecord` is a storage-port snapshot, mapping persisted state back to the aggregate while keeping lease/version metadata outside the domain. [Domain model and class diagram](booking-sqlite.md#domain-model).

## Compatibility

Endpoint URLs, request/response JSON field names, response envelopes, database schemas, and configuration property names are preserved. Extracting `ShipmentParcel` preserves the exact previous booking normalization and canonical JSON; a fixture captured from the old compiled implementation checks its fingerprint and persisted-claim replay. Quote parcel values are normalized before carrier dispatch.

The demo scenario remains part of the booking command to preserve existing persisted fingerprints; removing it from stored intent would require an explicit compatibility strategy. Simulated endpoints and carrier storage are grouped under `demo` and remain profile-gated.
