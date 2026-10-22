# Demo scenario and engineering goals

An independent engineering exercise using simulated carrier APIs and synthetic scenarios. The shipping domain supplies concrete examples of common distributed-system failure modes; it does not describe a particular company's products or internal systems.

## Implemented workflows

| Workflow | User need | Engineering focus |
| --- | --- | --- |
| Compare rates | Obtain available shipping options within a predictable wait | Concurrent fan-out, global deadlines, partial results, circuit breakers and capacity isolation |
| Create a shipment | Avoid duplicate side effects when requests are retried | Durable idempotency, explicit state transitions and inquiry-only reconciliation |
| Observe outcomes | Understand latency, capacity and uncertain operations | Correlated operational logs, typed error outcomes and failure-isolated metrics |

## Design assumptions

- Carrier integrations are simulated. Names used by local adapter examples identify test scenarios; no live carrier contract is implemented.
- Rate inputs use explicit measurement units and a configured comparison currency/market.
- A submit timeout cannot establish whether the carrier created a shipment. Persist the attempt first and retain a stable lookup identity.
- Idempotency applies to an owner's request key and normalized intent. Different keys represent different operations.
- Numerical capacity examples are illustrative assumptions, not production measurements.

## Growth boundaries

Stateless quote workflows can run on additional replicas, subject to fleet-wide carrier capacity. Booking persistence is accessed through ports and carries an immutable logical locator, allowing future shard routing. Idempotency scope must remain co-located or be coordinated separately; UUID-based distribution alone is insufficient.

Reconciliation owns its task table and reaches booking state through a neutral contract. This supports later process extraction without direct cross-module database writes. The current application uses local SQLite files, not a distributed database or deployed service cluster.

## Evaluation

Demonstrate normal, partial-failure and uncertain-write behavior. Explain the correctness guarantees, their external dependencies, and the difference between implemented modules and proposed deployment extensions. Tests and code entry points provide evidence for each implemented claim.
