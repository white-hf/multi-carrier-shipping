# Code organization

One Maven project and one Spring Boot application. Business capabilities own their code; shared contracts and deployment wiring are explicit.

```text
com.enterprise.shipping
├── Application.java
├── quote/                    Rate comparison
│   ├── api/                  HTTP input and error mapping
│   ├── application/          Fan-out orchestration
│   ├── domain/               Commands, quotes and outcomes
│   ├── port/                 Carrier and observer interfaces
│   ├── infrastructure/       Monitoring adapters
│   └── config/               Module wiring and settings
├── booking/                  Shipment creation and business state
│   └── {api,application,domain,port,infrastructure,config}/
├── reconciliation/           Recovery task ownership and inquiry
│   └── {application,domain,port,infrastructure,config}/
├── contracts/recovery/       Booking/reconciliation communication contract
├── runtime/                  Cross-module deployment wiring only
├── shared/
│   ├── api/                  Response envelope, parcel input and safe errors
│   ├── domain/               Parcel, units and market rules
│   ├── identity/             UUIDv7 generation policy
│   ├── infrastructure/       Reusable HTTP, monitoring and SQLite setup
│   └── config/               Market configuration
└── demo/{quote,booking}/     Simulators, fault modes and demo wiring
```

## Module ownership

Controllers translate requests; application services coordinate through ports; domain classes enforce business rules. Infrastructure implements database, HTTP and monitoring details. Each module's `config` package assembles its own components. Packages are added only when needed: reconciliation has no public HTTP API, and quote has no database repository.

`ShipmentBooking` owns receipt validation and lifecycle transitions. `BookingRecord` rehydrates it while keeping the persistence version and submission deadline outside the aggregate. Recovery scheduling data belongs to the separate reconciliation task table.

`ReconciliationConfiguration` lives in `reconciliation/config` with its properties. The storage exception lives beside `ReconciliationTaskStore`, because it describes a persistence-port failure rather than a shipment business rule.

## Communication and deployment

- `contracts/recovery` contains `BookingRecoveryPort` and minimal discovery/observation records. Booking implements the boundary; reconciliation consumes it. Neither module imports the other's implementation.
- `runtime/RecoveryRuntimeConfiguration` binds reconciliation's datasource and clock to the current application's resources. This deployment currently shares booking's datasource; module configuration does not select the other module's resources.
- `shared` imports no business module. `SqliteDataSources` lives in shared infrastructure because both booking and the simulated external carrier use the setup helper, with separate database files.
- `demo` implements core ports; business code imports no simulator. Quote, submission and inquiry remain separate capabilities with different failure semantics.

These are package boundaries within one build. Separate artifacts and remote transport are future extraction work, not current deployment features.

## Reuse and operation

Parcel inputs normalize CM/IN and KG/LB through shared value types before hashing or carrier dispatch. `BookingRequestHasher` fingerprints intent; business columns remain authoritative and API responses are reconstructed from persisted state.

The shared HTTP factory creates separate pools for each carrier capability. Booking submission disables automatic transport retries. Spring AOP and observer ports isolate monitoring from business decisions and preserve bounded metric labels.

Booking and reconciliation components load unconditionally. Quote activation and reconciliation scheduling remain configurable. Outside the demo profile, required market, carrier and ownership infrastructure must be supplied. Development schema changes use fresh database paths; no compatibility layer or automatic migration is provided.

[Code entry points](implementation-map.md) · [Booking model](booking-sqlite.md) · [Recovery design](reconciliation.md) · [Runtime boundaries](runtime-boundaries.md)
