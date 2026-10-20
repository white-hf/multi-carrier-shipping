# Booking domain model validation

Validated October 20, 2026 on Java 17. `./mvnw clean package` passes all 63 tests with no failures or skipped tests: 15 quote tests, 25 booking workflow/API tests, and 23 plain domain tests. The previous 38 tests remain, with 25 new cases.

Domain tests cover matching receipt evidence, rejection versus uncertainty, manual-review prerequisites, blocked transitions from confirmed/failed/manual-review states, invalid-evidence atomicity, consistent rehydration, and identity validation. They require no Spring, database, network, mocks, or clock. Workflow regressions verify recovery from malformed submission evidence and rejection of cross-identity persistence.

The unique claim and version/token SQL predicates remain unchanged. Existing tests still verify concurrent submission ownership, competing leases, stale-writer fencing, lock contention, stable snapshots, legacy fingerprints, metrics isolation, and restart recovery.

A cross-version packaged check creates bookings using the previous build, then starts the aggregate version with the same SQLite files. The new version recovers the uncertain booking through inquiry, replays the previous terminal snapshot, and retains one simulated carrier submission per operation. The quote endpoint and Prometheus remain available. [Recorded outcomes](booking-domain-smoke-results.json).

Domain types have no framework or persistence imports; shared code has no dependency on either business feature. No schema, configuration, fingerprint, or API migration is required. This is a local simulated-carrier demo; distributed uniqueness still depends on the database, and external side-effect guarantees depend on carrier behavior.

[Domain model and class diagram](../design/booking-sqlite.md#domain-model) · [Code organization](../design/code-organization.md)
