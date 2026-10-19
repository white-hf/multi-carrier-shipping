# Proposed platform architecture

This is a platform design inferred from ShipTime's public product capabilities, not a claim about its internal architecture. Client channels, microservice deployment, database technology, and observability products are proposed choices. Only the rate aggregation demo is implemented in this repository.

## Platform view

[![Proposed shipping platform architecture](platform-architecture.svg)](platform-architecture.svg)

## Design boundaries

- **Gateway:** authenticates clients, applies access/rate policies, standardizes idempotency-key handling, and propagates trace context. Durable booking deduplication and request/result consistency remain the shipment service's responsibility; the gateway cannot make a remote courier booking atomic.
- **Business services:** rate, shipment/labels/returns, tracking, orders/fulfillment, and accounts/billing have separate ownership and can scale independently. The diagram focuses on core boundaries; notifications and support tools can consume domain events without being part of rate lookup.
- **Outbound adapters:** courier adapters handle rate, booking, and tracking calls; ecommerce adapters import orders and synchronize fulfillment. Courier bulkheads, connection limits, and circuit breakers prevent one dependency from consuming another's capacity. Real-time rate requests do not wait behind webhook queues.
- **Inbound adapters:** webhooks use provider-appropriate signature/authentication and replay checks rather than merchant JWT authentication. Handlers validate and normalize payloads and acknowledge only after durable acceptance. Where webhooks are unavailable or updates are stale, bounded polling uses the same carrier normalization rules.
- **Messaging and consumers:** tracking and commerce have separate queues/topics, consumer groups, retry policies, and dead-letter handling. Consumers expect redelivery, deduplicate events, and commit database changes before acknowledging messages/committing offsets. Tracking consumers preserve event history and apply guarded projections; commerce consumers reconcile orders and synchronize fulfillment according to event type.
- **Domain events:** shipment-booked events can trigger ecommerce fulfillment write-back. If publishing an event accompanies a database change, use a transactional outbox and relay; a database commit followed by an unprotected broker publish can lose the event. This internal event path is omitted from the overview to keep external integrations readable.
- **Data ownership:** quote context, shipment/labels, tracking, orders, and accounts/billing use separate schemas or databases with service-owned write access. APIs and consumer workers in one domain share its data ownership. No cross-service table writes or shared transactional database are assumed. Separate physical database servers are optional; this is an ownership boundary, not a requirement for one server per service. Label files and documents belong in object storage, with metadata in the shipment domain.
- **Observability:** gateway, services, adapters, and consumers emit telemetry through an instrumented path. Propagate trace context over HTTP and message headers; correlate logs with request/trace IDs. The OpenTelemetry Collector exports signals to the chosen metrics, log, and tracing backends. Export is buffered and bounded so backend outages do not become synchronous business dependencies. Product names in the diagram are examples, not confirmed ShipTime technologies.

## Implemented versus proposed

The Java 17 demo implements one rate API, concurrent aggregation, simulated courier adapters, per-carrier resilience/isolation, and Micrometer/AOP monitoring. The gateway, independently deployed microservices, databases, broker, webhook consumers, ecommerce adapters, object store, and OpenTelemetry/log/trace backends shown here are design context and are not shipped infrastructure.

## References

- Product capabilities: [ShipTime](https://shiptime.com/), [shipping API](https://shiptime.com/shipping-api/), [ecommerce workflows](https://shiptime.com/shiptime-updates-features/revamped-ecommerce-shipping/).
- Delivery/acknowledgement semantics: [RabbitMQ reliability guidance](https://www.rabbitmq.com/docs/reliability).
- Side-effect safety: [AWS idempotent API guidance](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/).
- Telemetry pipeline: [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/).
