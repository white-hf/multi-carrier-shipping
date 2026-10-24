# Module boundaries and shared infrastructure

Date: 2026-10-23
Status: Implemented in I8; SQLite backend and existing browser journey verified.

## Responsibilities and dependencies

The sandbox is a shipping provider/aggregator, not an individual carrier. Keep provider and carrier identities separate. Replace the broad `integration` package with `provider/sandbox` for external API communication and provider-specific payloads. Business modules own their outbound ports; shared infrastructure does not own business contracts.

```text
quote -> RateProviderPort <- SandboxRateAdapter -> SandboxProviderClient
booking -> ShipmentProviderPort <- SandboxShipmentAdapter -> SandboxProviderClient
booking -> LabelProviderPort <- SandboxLabelAdapter -> SandboxProviderClient
reconciliation -> RecoveryCapabilityPort <- SandboxRecoveryAdapter -> provider capabilities
```

Business modules must not import provider implementations. Thin bridges implement consumer ports and translate consumer models to provider models. `provider` must not import quote, booking or reconciliation. A composition root wires implementations to ports. Existing public business contracts, such as quote selection and shipment recovery, remain owned by the supplying module; they do not move into `shared`.

## Target organization

```text
shipping/
  auth/                          # api, application, infrastructure, config
  quote/                         # api, application, contract, domain, port, infrastructure
    infrastructure/provider/     # SandboxRateAdapter
  booking/                       # same organization
    infrastructure/provider/     # SandboxShipmentAdapter, SandboxLabelAdapter
  reconciliation/                # same organization
    infrastructure/provider/     # SandboxRecoveryAdapter
  provider/sandbox/
    SandboxProviderClient.java
    SandboxPayloadMapper.java
    model/                       # provider-specific request/response types
    config/
  runtime/                       # deployment composition, including SandboxConfiguration
  shared/
    identity/
    time/
    api/
    infrastructure/
      http/
      observability/
      security/
      persistence/
```

Use these packages only where needed; do not create empty layers. Provider code may be packaged with each future service; this design does not require a central provider microservice or a shared database. Service extraction still needs deployment, API and data ownership work.

## Shared outbound calls

Consolidate existing `CarrierHttpClientFactory`, adapter resilience setup and transport monitoring. One implementation owns each mechanism, with explicit policy per provider, account and operation where isolation requires it.

| Shared mechanism | Responsibility |
| --- | --- |
| HTTP client factory | TLS, bounded connection pools/acquisition waits, connect/read timeouts, response-size limits and lifecycle cleanup |
| Call executor | Remaining deadline, bounded bulkhead admission, circuit breaker, cancellation where supported and error classification |
| Retry policy | Explicit safety eligibility, bounded attempts, backoff/jitter and remaining-budget checks |
| Observability | One transport measurement per actual attempt, one logical-call outcome, structured redacted logs and trace propagation |
| Transport errors | Distinguish timeout, connection failure, HTTP rejection, capacity rejection and invalid responses; modules map these to their public errors |

Share implementation, not one global runtime resource. Keep separate rate/create/label pools, bulkheads and breakers. Preserve existing operation capacity limits. Specify breaker and retry ordering explicitly, including which failures count; do not nest multiple retry layers. Pool acquisition and retry delays consume the same overall deadline.

Shipment creation defaults to no automatic retry, including hidden transport retries. A write retry is permitted only with an explicitly verified provider guarantee. Read-only rate/label operations may opt into bounded retries; HTTP method alone does not establish safety. Unknown creation outcomes remain eligible only for the existing capability-aware recovery/manual-review flow.

Fan-out retains ownership of aggregate deadlines, concurrent scheduling and partial results. Each child call uses shared outbound infrastructure with its remaining budget; fan-out does not reimplement HTTP resilience. Legacy demo paths must preserve behavior during migration.

Metric dimensions are bounded provider/operation/outcome values. Never use request IDs, raw URLs, tracking numbers or user IDs as metric tags. Prevent duplicate AOP/executor measurement when consolidating instrumentation. Preserve both business observers and transport metrics because they measure different boundaries.

## Other shared components

- Injected clocks and UUID generation.
- Request/trace context and credential/PII redaction.
- External URL/download safeguards, including allowed hosts, redirects and bounded response sizes. Provider-specific authentication and payload interpretation stay in adapters.
- API error envelopes; business error definitions stay with their module.
- Small persistence/configuration helpers with explicit database-specific behavior. I7 has been tested on SQLite; MySQL/PostgreSQL compatibility remains unverified.
- Reusable test clocks, controlled HTTP stubs and resilience fixtures in test support, not production shared code.

Do not introduce generic base services/repositories or a shared business DTO model. Move code to shared only when the responsibility is stable and has a demonstrated consumer.

## Domain behavior and data representations

`ShipmentBooking` already owns state transitions and receipt invariants. `RateRequest` already owns matching/retry eligibility and claim version advancement. Records can be domain objects; classify by responsibility rather than Java syntax.

Strengthen these boundaries without inventing aggregates solely to relocate fields:

| Type | Owned rules |
| --- | --- |
| RateRequest | Request identity/content matching, legal execution lifecycle and retry eligibility |
| ShippingQuote | Validity, expiry and acceptance eligibility |
| ShipmentBooking | Confirmation, definitive rejection, uncertainty, manual review and receipt consistency |
| Value objects | Identity, money, measurements and unit invariants where they reduce duplicated rules |

Application services orchestrate remote calls and repositories. Repositories enforce durable uniqueness and version compare-and-set; domain methods cannot replace distributed concurrency protection. Keep technical execution budgets in application/infrastructure, separate from business quote validity.

HTTP DTOs belong in `api`; use-case commands/results in `application`; public cross-module DTOs in their owning `contract`; provider payloads in provider `model`; persistence rows/mappers in `infrastructure/persistence`. Domain values remain in `domain`. Avoid generic `bean`, `dto` dumping grounds and unnecessary model subpackages.

## Verification and rollout

Refactor in small slices: provider bridges, shared transport, then domain responsibility cleanup. Preserve frontend/API contracts, four application tables, request/booking idempotency, isolation and manual-review behavior. No frontend implementation belongs to I8.

Verify dependency directions, no provider-to-business imports, no business-to-provider implementation imports, single retry/transport instrumentation ownership, operation isolation, deadline exhaustion, circuit recovery, no automatic create retries and domain transition invariants. Run backend regression and the existing browser acceptance journey using local provider stubs; no chargeable external creation is required. Record actual evidence before marking I8 accepted.

## I8 implementation notes

`SandboxProviderClient` now owns provider transport and response handling; `SandboxPayloadMapper` owns provider request/unit mapping. Four consumer-owned adapters translate provider values without a provider-to-business dependency. Deployment wiring lives in `runtime/SandboxConfiguration`.

`OutboundOperation` owns isolated pool/bulkhead/breaker instances and shared construction defaults; `OutboundCallExecutor` owns guard order and transport failure mapping; `OutboundDeadline` owns blocking response-budget enforcement and cancellation. Fan-out uses the same guard primitive while keeping its existing orchestration and carrier error vocabulary. `CarrierHttpClientFactory` remains the shared HTTP transport constructor.

Existing shared monitoring AOP remains on provider-client operations and carrier adapters; thin bridges add no duplicate annotations. Metrics and public errors retain their existing names. Automatic application retries remain disabled; provider transport retries remain disabled. Bounded read retries are a future opt-in capability, not added by this refactoring.

`ShippingQuote.eligibility` owns the provider-token/expiry acceptance rule. Existing `RateRequest` and `ShipmentBooking` retain lifecycle rules. Booking/quote use-case commands and the booking result projection moved from domain into application; domain value records remain in domain. Existing port request/response records remain consumer-owned contracts; no universal shared DTO is introduced.

No frontend, API contract, database migration or service deployment changes are included. MySQL/PostgreSQL support remains unverified.

## Authentication boundary clarification

Authentication is a peer module, not shared infrastructure. `auth/api/WorkflowAuthController` owns guest token issuance and cookie delivery; `auth/application/GuestSessionTokenService` owns token signing, verification and expiry. `auth/infrastructure/WorkflowAuthenticationFilter` owns workflow API authentication and same-origin write protection. `auth/config/WorkflowAuthConfiguration` wires token policy and resolves identity through the shared `UserContext` contract.

`shared/api/WorkflowRequestTracingFilter` owns correlation IDs, MDC lifecycle and common response headers. Explicit filter order runs tracing before authentication so rejected requests retain diagnostic context. Business modules continue using `UserContext` without importing token implementations. `shared` imports neither auth implementations nor concrete provider clients. Deployment-specific provider wiring belongs to `runtime`, where cross-module composition is intentional.

This is a small organizational correction to the existing design, not a new iteration. Public endpoints, token/cookie formats, configuration keys, authorization behavior and frontend files remain unchanged.
