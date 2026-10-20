# SQLite shipment booking iteration

## Outcome

Implement a local, restartable booking API that demonstrates durable idempotency, one initial submitter, explicit uncertain outcomes, and inquiry-only reconciliation. Preserve the existing rate API.

## Scope and decisions

- Java 17 / Spring MVC; feature enabled only with the demo profile.
- Fixed server-owned demo merchant. No client-supplied ownership or production authentication.
- Spring JDBC and SQLite WAL/FULL synchronization; short atomic SQL operations, no carrier call inside a database transaction.
- Unique owner/key claim, normalized request hash, persisted carrier/reference/request before submission.
- Version/token-fenced state updates. Leases allow inquiry ownership, never permission to blindly resubmit.
- Separate SQLite database for the simulated HTTP carrier. A timeout-after-accept scenario proves recovery across independent commits.
- Terminal response snapshots, GET booking status, bounded scheduled inquiry with retry backoff and manual-review state.
- Telemetry behind a booking observer port; logs contain IDs/outcomes, not request payloads or raw keys.

## Acceptance

Concurrent retries produce one claim and one initial submission; payload mismatch conflicts; timeout does not create a duplicate; stale PENDING recovers through inquiry; restart preserves state; competing workers cannot overwrite confirmation; lock contention differs from key collision; terminal replay returns the saved response. Real file-backed SQLite tests and HTTP integration tests must pass alongside rate tests.

## Limits

No real carrier, financial charge, slot reservation, quote authorization, production throughput claim, guest ownership, or automatic resubmission after an unknown outcome. SQLite allows one writer per database. Database schema changes beyond this initial demo require migrations.
