# Iteration 08 — Module boundaries and shared outbound infrastructure

Date: 2026-10-23
Status: Implementation and regression verification completed on 2026-10-23; awaiting user acceptance.
Design: [Module boundaries](../design/module-boundaries.md).

## Outcome and scope

Make provider integration independent of business modules, consolidate shared HTTP resilience/observability, and clarify domain responsibilities. Preserve all public behavior. No frontend changes, schema changes, service deployment or new provider integration.

| Task | Deliverable |
| --- | --- |
| ST-801 | Split SandboxShippingAdapter into consumer-owned bridges and a provider client/model that imports no business modules; update composition wiring |
| ST-802 | Consolidate HTTP client construction, deadline/resilience policies and monitoring; preserve per-operation resource isolation and disabled creation retries |
| ST-803 | Consolidate quote eligibility and request/booking lifecycle rules in existing domain objects; move transport/use-case/persistence representations to their owning layers |
| ST-804 | Verify dependency rules, resilience and domain invariants; run backend regression and existing browser acceptance; update design and execution summary |

## Acceptance criteria

- Provider implementation imports no quote/booking/reconciliation types; business logic imports no concrete provider implementation.
- Shared transport has one implementation per mechanism with explicit policies and bounded resources. Fan-out uses it for child calls.
- No hidden creation retry; unknown outcomes retain existing manual-review safeguards.
- Business and transport observability remain distinct without duplicate transport measurements or unbounded metric dimensions.
- Domain behavior and DTO placement follow the agreed design; no generic bean package or unnecessary base classes.
- Existing API, data and user journeys remain compatible. Backend and existing browser tests pass with local stubs.
- Execution evidence and residual risks are recorded before acceptance. Current I7 evidence is SQLite-only; this iteration makes no additional database compatibility claim.

## Risks and controls

Preserve connection pool lifecycle, circuit/bulkhead identities, AOP interception and metric names during extraction. Avoid nested retry/deadline handling. Move one boundary at a time and verify each slice. DTO translation is deliberate isolation, not permission to duplicate business rules.

## Execution evidence

See [I8 summary](../summaries/iteration-08-module-boundaries.md): 153 backend tests and one existing browser acceptance test passed with local provider stubs.
