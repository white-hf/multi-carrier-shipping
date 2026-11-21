# Compact shipping design — I12

Status: implemented and verified in the current worktree. Public API, auth, schema and snapshot
field names are unchanged.

## Authorized simplification boundary

The user approved:

- one shipping business feature for rates, booking and labels, instead of separate
  quote/booking/reconciliation business modules;
- retiring the direct-carrier fan-out demo;
- retiring the generic outbound template/dependency registration and logical groups;
- using Spring HTTP interfaces and native Resilience4j operators instead of a custom invocation
  framework.

The approval does **not** retire:

- dependency inversion for external capabilities;
- bidirectional provider isolation;
- independent provider API slices;
- the separation between shipping and reconciliation.

## Modules and responsibilities

```text
com.enterprise.shipping/
├─ auth/                          Account identity, tokens and HTTP authentication
├─ shipping/                      Rates, booking lifecycle and labels (one feature)
│  ├─ api/                        ShippingController, BookingResponse
│  ├─ application/                QuoteService, BookingService, BookingCommand, RecoveryOperations
│  ├─ contract/                   ShipmentRecovery, Candidate, ManualReviewReason
│  ├─ model/                      ShipmentBooking, ShippingQuote, CreateResult, Label…
│  ├─ port/                       RateProvider, ShipmentProvider, LabelProvider
│  ├─ infrastructure/provider/    Sandbox adapters implementing the ports
│  ├─ persistence/                BookingRepository
│  └─ config/                     ShippingConfiguration composition root
├─ provider/sandbox/              Provider-owned protocol: rate/, shipment/, label/
├─ reconciliation/                RecoveryJob, ReviewTaskRepository, ReconciliationConfiguration
└─ shared/infrastructure/transport/  Pool/WebClient/strict JSON, protection, diagnostics
```

`ShippingController` intentionally remains one file: quotes, bookings and labels are the same
feature and route family. `LabelProvider` stays behind `BookingService`; label reads share the
booking row, version fence and `label_status`, so a separate `LabelService` would split one
aggregate's use cases without reducing coupling.

## Dependency direction

```text
QuoteService   → RateProvider
BookingService → ShipmentProvider, LabelProvider
ShippingConfiguration → adapters → provider operations
ReconciliationJob → ShipmentRecovery (shipping.contract) + ReviewTaskRepository
provider/sandbox/* → shared transport only; never shipping
```

- Ports are consumer-owned: `shipping/port` defines what the business needs; adapters are the only
  classes that see both a port and provider types.
- provider must not import `com.enterprise.shipping.shipping.*`, `reconciliation`, `auth` or
  `shared.api`.
- shipping application, model, port, contract and api must not import `provider`.
- Only `shipping/config` and `shipping/infrastructure/provider` may reference provider.
- Provider slices are organized by vendor API resource, not by our business capability. If one
  vendor endpoint returns rates and a draft shipment and a second call pays for it, that is one
  provider slice; the adapters still implement `RateProvider` and `ShipmentProvider`.
- A provider quote handle is opaque. Business code never interprets it as a quote token or draft id.

## Provider isolation

Provider-owned types (`RateOffer`, `CreationOutcome`, `LabelData`, `CreateSubmission`,
`LabelIdentity`) never cross into application/model/api. Adapters map them to `ShippingQuote`,
`CreateResult` and `Label` without changing status meaning.

Adding an API adds its HTTP interface, operation, DTOs and proxy configuration under
`provider/sandbox/<resource>/`; it does not modify existing slices or the shared transport.
Adding another vendor adds `provider/<vendor>/…` plus new adapters; shipping services remain
unchanged. A second provider is also the trigger to add a minimal port interface only if the
existing capability ports are insufficient.

## Shared mechanisms only

`shared/infrastructure/transport` provides:

- connection-pool/WebClient construction, Basic authentication, same-origin/traversal checks,
  strict JSON codecs, response-size bounds and body buffering;
- `OutboundProtection`: native Resilience4j bulkhead and circuit operators, remaining deadline,
  interruption handling and metrics;
- bounded/redacted diagnostics.

It does not provide templates, dependency registration, operation registries, logical groups or a
custom execution DSL. Operation is a metric tag only; adding an API never edits the shared layer.

Semantics stay in the owning operation: rates 404 is an expected empty result, create 400 is a
definite rejection, admission rejection maps to `NOT_DISPATCHED`, and ambiguous create outcomes
stay `UNKNOWN` and are never retried automatically.

## Reconciliation is separate

Reconciliation remains a distinct module with its own table and scheduling. It depends on shipping
only through `shipping.contract.ShipmentRecovery` and immutable `Candidate`/`ManualReviewReason`
values. It never reads or updates `shipment_bookings` directly. Shipping never imports
reconciliation.

## Verification

- Source-level boundary checks: provider isolation, independent provider slices, framework-free
  model/ports, controller/persistence separation.
- Source checks: provider slices independent, transport free of provider/business knowledge,
  controllers do not reach persistence/provider, table ownership.
- Behavior: SQLite claim/retry/restart, provider protocol/deadline/body/circuit, strict JSON and
  label allowlist, full API regression, packaged startup and browser journey.
