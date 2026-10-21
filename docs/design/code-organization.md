# Code organization

One Maven project and one Spring Boot application, organized first by business feature.

```text
com.enterprise.shipping
├── quote/
│   ├── api/
│   ├── application/
│   ├── domain/
│   ├── port/
│   ├── infrastructure/monitoring/
│   └── config/
├── booking/
│   ├── api/
│   ├── application/
│   ├── domain/
│   ├── port/
│   ├── infrastructure/{persistence,monitoring,security}/
│   └── config/
├── reconciliation/{application,domain,port,config,infrastructure}/
├── contracts/recovery/
├── runtime/
├── shared/
│   ├── api/
│   ├── domain/
│   ├── config/
│   └── infrastructure/{http,monitoring}/
└── demo/{quote,booking}/
```

## Boundaries

Controllers map validated input to application commands. Application services coordinate workflows through ports. The booking aggregate and domain value types contain no Spring, HTTP-client, or database dependencies. Infrastructure implements carrier, storage, and observer ports; configuration wires dependencies and owns pool shutdown. Tests mirror the feature packages.

Business features and reconciliation do not import each other. They communicate through `contracts.recovery`; runtime configuration binds implementations. Shared code imports no feature. Quote infrastructure has no persistence package because it currently needs no database.

## Reuse

| Component | Reused responsibility |
| --- | --- |
| `shared.api.ParcelRequest` | Validated parcel fields; quote input remains flat, booking input keeps its nested `parcel` |
| `shared.domain.ShipmentParcel` | Immutable normalized postal codes/countries and kg/cm measurements |
| `DimensionUnit`, `WeightUnit` | Exact decimal conversion from CM/IN and KG/LB before hashing and carrier dispatch |
| `shared.api.ApiResponse`, `CommonExceptionHandler` | Response envelope and generic safe HTTP errors |
| `CarrierHttpClientFactory` | Bounded pool construction, deadlines, response-size limits, warmup |
| Carrier monitoring annotation/aspect | Transport timings with failure-isolated recording |

The HTTP factory creates independent resources. FedEx quotes, UPS quotes, and booking have separate pools. Retry policy is explicit: the existing quote transport behavior is preserved, while booking disables automatic transport retries.

`CarrierRatePort` and `CarrierBookingPort` remain separate capability contracts. Quote deadlines/partial results and booking claims/reconciliation remain independent workflows. `BookingRequestHasher` calculates intent fingerprints. Structured business columns are the authoritative persisted state, and responses are reconstructed from the aggregate.

`QuoteObserver` and `BookingObserver` separate application events from Micrometer. Their implementations preserve existing metric names and isolate recording failures. The quote worker gauge retains its supplier for the registry lifetime.

## Booking domain behavior

`ShipmentBooking` owns receipt validation and lifecycle transitions. Application services invoke its behavior instead of assigning statuses or constructing arbitrary outcomes. `BookingRecord` is a storage-port snapshot, mapping persisted state back to the aggregate while keeping storage version/deadline metadata outside the domain. [Domain model and class diagram](booking-sqlite.md#domain-model).

## Runtime selection and compatibility

Business controllers, services, and handlers are feature-gated, independently of the demo profile. Simulated protocol adapters, connection wiring, fault modes, carrier database, and fixed owner are under `demo`. Core code imports no demo package. [Runtime boundaries](runtime-boundaries.md).

Request intent contains only business fields. Optional country codes resolve from the configured default before hashing. Only the current development format is supported; no compatibility layer or automatic data migration is provided. Use fresh local demo database files after intent model changes. Incoming simulator fields are rejected rather than ignored. See [booking runbook](../runbooks/booking-demo.md).
