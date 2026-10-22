# Engineering discussion guide

## Opening — about 30 seconds

“This is an independent demo of designing distributed applications for failure. Rate comparison demonstrates bounded concurrent calls, deadlines, circuit breakers and isolation. Shipment creation demonstrates durable idempotency, state transitions and reconciliation when a carrier's response is lost. I kept the modules behind interfaces so we can discuss how to scale them or extract services.”

## Suggested five-minute path

1. Read path: run a comparison with one slow carrier and explain partial results and the global deadline.
2. Resource protection: show per-carrier workers, connection pools, bulkheads and circuit breakers.
3. Write path: show the unique claim, request fingerprint and booking aggregate.
4. Recovery: show timeout-after-accept, persisted task evidence and idempotent delivery after restart.
5. Operations and growth: show logs/metrics, explain module boundaries, and identify what remains necessary for sharding and multi-host deployment.

## Short technical answers

**What does the deadline guarantee?**

“It bounds the orchestration's waiting and preserves available results. Transport timeouts and cooperative cancellation also matter; cancellation does not prove that a remote operation stopped.”

**Why use a circuit breaker and a bulkhead?**

“A breaker controls calls to an unhealthy dependency. A bulkhead caps the resources it can consume. Separate connection pools prevent one carrier from using another's allocated connections.”

**What if shipment submission times out?**

“I retain an unknown outcome and query the original carrier/account/reference. Recovery cannot submit a shipment. Finding no result is not permission to create another one.”

**Is this a distributed ACID transaction?**

“No. Database writes are locally atomic; the remote outcome is reconciled. The guarantee depends on the carrier's idempotency and lookup contract.”

**What if booking confirmation succeeds but recovery loses the acknowledgement?**

“The task has already saved its observation. It redelivers that observation, and the booking handler acknowledges the matching confirmed receipt without another business write.”

**Can this run as a cluster today?**

“The quote workflow is stateless, but limits and breakers are per instance. Multi-host booking needs a production database. Sharding also needs a routing strategy that preserves owner-scoped idempotency; the current locator is a boundary for that work, not a shard router.”

**Why keep one process?**

“Separate module and data ownership first. Extract a service when independent capacity, operational ownership or deployment requirements justify the transport and coordination costs.”

## Phrases to support discussion

- “Let me show that in the code.”
- “Could you repeat the last part, please?”
- “Are you asking about correctness, performance, or the failure path?”
- “My assumption here is ____. If that changes, I would reconsider ____.”
- “That is a design extension; it is not implemented in this demo.”
