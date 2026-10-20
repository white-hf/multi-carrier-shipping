# Interview project delivery

## Objective

Provide a concise English case study linking shipping business capabilities to technical challenges, architecture decisions, and a working rate comparison module.

## Delivered

- Public-source product research with explicit architecture assumptions.
- Business-to-architecture explanation and focused module/class diagrams.
- Java 17 rate comparison with bounded courier resources, deadlines, partial results, and typed failure outcomes.
- AOP transport monitoring, Prometheus metrics, and deterministic mock scenarios.
- Maven wrapper and 14 passing engine/API tests.
- Executable jar and normal, partial-failure, and total-failure smoke checks.

See the [implementation iteration](java17.md), [validation evidence](../summaries/java17.md), and [demo runbook](../runbooks/demo.md).

## Scope boundaries

Carrier contracts are simulated. Real carrier integration, account pricing, and tracking processing are not implemented. Shipment booking is implemented as a local SQLite-backed simulation. Historical project stories require candidate confirmation. This repository is a local engineering demonstration, not a production shipping service.
