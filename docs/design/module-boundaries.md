# Module Boundaries and Code Organization

The application is a **modular monolith**: one Spring Boot process organized by business
capability. Ports and adapters keep business rules separate from persistence and external
protocols.

## I12 boundary change

The [compact shipping design](compact-shipping.md) merges quote, booking and label behavior into
one `shipping` feature, retires direct-carrier fan-out and removes generic outbound
templates/dependency registration. It preserves dependency inversion, provider isolation and
independent provider API slices.

`reconciliation` remains a separate module: background overdue discovery and manual-review tasks
differ from request-driven rates/booking/labels and own a different table. It depends on shipping
only through `shipping.contract.ShipmentRecovery`.

## Design Principles

1. **Unidirectional dependencies, acyclic dependencies (ADP) and dependency inversion (DIP)**

   Module dependencies are acyclic and technical dependencies point inward. Application services
   depend on consumer-owned capability ports; adapters implement those ports; provider protocol
   code never depends on business modules.

2. **Vertical business-capability decomposition**

   Organize by capability (`auth`, `shipping`, `reconciliation`), not by global controller/service/
   repository layers. A capability's rules, use cases and adapters stay together.

3. **Inter-module decoupling through published contracts**

   Business modules use a peer's `contract` types only. Shipping publishes
   `ShipmentRecovery`/`Candidate`/`ManualReviewReason`; reconciliation never imports shipping
   application, persistence, provider or API internals.

4. **Strict table ownership**

   Shipping owns `shipment_bookings`; reconciliation owns `booking_reconciliation_tasks`.
   Cross-module access uses identifiers and the published contract, never SQL joins or foreign
   keys.

5. **Bidirectional provider isolation**

   Shipping cores do not import concrete providers. Provider implementations do not import shipping
   business modules. Consumer-owned adapters under `shipping/infrastructure/provider` translate
   between capability ports and provider-owned models. Provider API slices are independent and are
   organized by the vendor's API resources, not by our business capabilities.

6. **Framework-free domain and centralized HTTP error mapping**

   `shipping/model`, `shipping/port` and `shipping/contract` do not depend on Spring, JDBC or
   Reactor. HTTP status, links and response metadata are mapped at `shipping/api` and the shared
   exception handler.

## Code Organization

Root: `src/main/java/com/enterprise/shipping/`

```text
auth/             Account identity, token validation and HTTP authentication
shipping/         Rates, booking lifecycle and labels (one capability)
  api/            Controller and HTTP response projections
  application/    Use-case orchestration and the published recovery implementation
  contract/       Published recovery contract and reason vocabulary
  model/          Business state, transitions and immutable values
  port/           Consumer-owned capability ports
  infrastructure/provider/  Sandbox adapters mapping provider types to business types
  persistence/    Booking repository and expected-version facts
  config/         Shipping composition root
provider/         Provider-owned protocol, isolated per vendor and API resource
  sandbox/
    rate/         Rates HTTP interface, operation, DTOs
    shipment/     Create HTTP interface, operation, DTOs
    label/        Label HTTP interface, operation, decoder, DTOs
reconciliation/   Overdue discovery, scheduling, review-task storage
shared/           Value types, persistence helpers, transport mechanisms, tracing
  infrastructure/transport/  Pool/WebClient, protection, diagnostics
```

Only `shipping/config` and `shipping/infrastructure/provider` may import provider implementations.
A provider API slice may use only shared mechanisms; it never imports another provider slice or a
business module.

## Dependency and Data Boundaries

| Consumer | Published boundary | Purpose |
| --- | --- | --- |
| `reconciliation` | `shipping.contract.ShipmentRecovery` | Discover and update unresolved bookings without touching booking tables |
| `shipping.application` | `shipping.port.*` | Invoke rates/create/labels without knowing vendor protocol |
| `shipping.infrastructure.provider` | `shipping.port.*` + `provider.*` | Translate provider evidence into business outcomes |
| `provider.*` | `shared.infrastructure.transport` | Reuse transport, protection and diagnostics mechanisms |

| Owner | Tables |
| --- | --- |
| `shipping` | `shipment_bookings` |
| `reconciliation` | `booking_reconciliation_tasks` |
| `auth` | None |
| `shared` | None |

[ModuleBoundariesTest](../../src/test/java/com/enterprise/shipping/shipping/ModuleBoundariesTest.java)
enforces selected source-level boundary rules. These are package conventions backed by
tests, not separate build modules or database permissions. Future service extraction still requires
remote contracts, deployment and data-consistency work.

For transaction flows, recovery behavior and schema details see [Backend Design](backend-design.md).
