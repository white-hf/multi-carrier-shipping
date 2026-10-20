# Shipment booking validation

Validated October 20, 2026 on Java 17. `./mvnw package` passes all 36 tests: 14 existing rate tests, 15 SQLite booking workflow tests, and 7 booking HTTP integration tests.

Tests cover concurrent claims, changed-intent conflicts, normalized replay, tenant isolation, actual SQLite lock contention, terminal snapshots, lease fencing, competing workers, interrupted submissions, bounded inquiry, and metrics failure isolation.

Packaged-process smoke checks used separate temporary database files and two successive JVMs. Twelve concurrent HTTP requests using one key produced one booking and one carrier submission. A changed payload returned 409; a definitive rejection returned 422. After carrier acceptance followed by an HTTP timeout, the API returned 202/PENDING_INQUIRY. A new JVM using the same files recovered CONFIRMED through inquiry, with the carrier submission count still one. Prometheus exposed booking metrics. The existing rate endpoint returned three quotes.

[Recorded smoke outcomes](booking-smoke-results.json) · [Reproduction instructions](../runbooks/booking-demo.md)

This validates a local simulated carrier, not real carrier contracts, financial settlement, production throughput, or resilience to storage loss. SQLite serializes writes; external exactly-once behavior depends on carrier guarantees.
