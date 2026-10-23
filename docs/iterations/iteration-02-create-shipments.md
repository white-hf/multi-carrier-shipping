# Iteration 02 — Safe shipment creation

Status: Verified against controlled local HTTP provider stub (API, SQLite concurrency, and Playwright E2E).
Requirements: FR-04–06, F12–13; ST-201–203, ST-301–303, ST-401.

Backend scope: claim-before-call, user/key and user/request uniqueness, persisted quote-only submission facts, stable operation replay, creation state independent of label availability, and durable manual-review discovery for unknown outcomes. No undocumented client-reference lookup, automatic creation retry or financial compensation is enabled.

Label scope is intentionally small: one frontend-triggered retrieval attempt for a pending label, then explicit manual retry. There is no background label worker or continuous polling requirement. This does not block displaying confirmed shipment creation.

Evidence required: concurrent same/different-key requests, changed intent, missing headers, accepted-but-lost response, invalid success receipt, restart, task deduplication, bounded discovery, label delay and label errors. Real HTTP stub tests verify serialization and wiring; direct service/SQLite tests verify state and concurrency. No tests create external shipments.

Exit requires frontend integration and the complete journey. Backend unit tests alone do not complete this iteration.
