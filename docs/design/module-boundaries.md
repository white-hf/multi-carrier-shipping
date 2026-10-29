# Module Boundaries and Code Organization

The application is a **modular monolith**: one Spring Boot process, organized by business capability. Ports and adapters keep business rules separate from persistence and external protocols.

## Design Principles

1. **Unidirectional Dependencies, Acyclic Dependencies Principle (ADP) & Dependency Inversion Principle (DIP)**

   Keep module dependencies acyclic and technical dependencies directed inward. Application services depend on module-owned ports; infrastructure implements those ports, keeping persistence and external protocols outside business logic.

2. **Vertical Business-Capability Decomposition (High Intra-Module Cohesion)**

   Organize `auth`, `quote`, `booking` and `reconciliation` by capability rather than global controller/service/repository layers. Keep each capability's business rules, state transitions and adapters together.

3. **Inter-Module Decoupling via Published Contracts**

   Business modules use peer modules' `contract` interfaces and immutable types, never their internal domain, application or repository implementations. Contracts make dependencies explicit and limit the impact of internal changes.

4. **Strict Database Table Ownership (No Cross-Module Joins)**

   Each business module exclusively accesses its own tables. Cross-module references use identifiers and contracts, without cross-module SQL joins or foreign keys. This reduces data coupling; cross-module consistency still requires explicit coordination.

5. **Bidirectional External Provider Isolation (Ports & Adapters)**

   Business cores do not import concrete providers, and provider implementations do not import business modules. Consumer-owned adapters translate between module ports and provider models; `runtime` composes them. Shared infrastructure supplies transport and resilience mechanisms rather than business policy.

6. **Framework-Free Domain & Centralized HTTP Error Mapping**

   Domain objects hold business invariants without Spring, JDBC or HTTP framework dependencies. HTTP response mapping belongs at the API boundary. Shared errors carry semantic failure categories, error codes and resource identities. Controllers and exception handlers map these to HTTP statuses, links and response headers.

## Code Organization

Root: `src/main/java/com/enterprise/shipping/`

```text
auth/             Guest identity, token validation and HTTP authentication
quote/            Shipping request snapshots, rate comparison and offers
booking/          Idempotent shipment creation, booking state and labels
reconciliation/   Overdue-booking discovery and recovery tasks
provider/         External API protocols, isolated by provider and per-API vertical slices
  aggregator/     Current aggregator provider (config/, rates/, shipments/, labels/)
shared/           Reusable types, HTTP guards, persistence helpers and tracing
runtime/          Deployment composition connecting modules to provider adapters
```

Business modules use the following packages where needed; small modules do not need every layer:

| Package | Responsibility |
| --- | --- |
| `api` | HTTP controllers and request/response mapping |
| `application` | Use-case orchestration and transaction coordination |
| `domain` | Business rules, state transitions and value objects |
| `port` | Interfaces the module uses for persistence, external calls and observation |
| `contract` | Interfaces and immutable types published to other business modules |
| `infrastructure` | Persistence, provider and monitoring adapters implementing ports |
| `config` | Module-local Spring wiring |

For example, `booking.domain.ShipmentBooking` owns booking transitions; `ShipmentApplicationService` coordinates the use case; `ShipmentStore` defines persistence access; `JdbcShipmentStore` implements it.

## Dependency and Data Boundaries

| Consumer | Published boundary | Purpose |
| --- | --- | --- |
| `booking` | `quote.contract.QuoteSelectionPort` | Resolve an owned, bookable quote |
| `reconciliation` | `booking.contract.ShipmentRecoveryPort` | Discover and update unresolved bookings |

External integrations follow the same separation: each business module owns a provider port and a thin adapter under `infrastructure/provider`. The adapter translates between business types and `provider.aggregator` models. **Provider code does not import business modules, and business cores do not import provider implementations.** `runtime.AggregatorConfiguration` connects them.

Shared outbound infrastructure owns dependency-level transport and admission, with independent circuits for configured logical groups. Business services do not duplicate these mechanisms. `shared` does not depend on business modules or concrete providers.

| Owner | Tables |
| --- | --- |
| `quote` | `shipment_requests`, `shipment_quotes` |
| `booking` | `shipment_bookings` |
| `reconciliation` | `booking_reconciliation_tasks` |

[ModuleBoundariesTest](../../src/test/java/com/enterprise/shipping/shipping/ModuleBoundariesTest.java) checks selected source-level dependency rules. These are package conventions backed by tests, not separate build modules or database permissions. Future service extraction still requires remote contracts, deployment and data-consistency work.

For transaction flows, recovery behavior and schema details, see [Backend Design](backend-design.md).
