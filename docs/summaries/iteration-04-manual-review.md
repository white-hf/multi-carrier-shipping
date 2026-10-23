# Iteration 04 — Manual review for unresolved shipments

Date: 2026-10-23
Status: **Accepted for the agreed demo scope.**
Scope: ST-404–406; clarification of uncertain-outcome behavior in FR-04.

## Delivered behavior

Creation timeout/lost response still records `PENDING_INQUIRY`. At the next eligible discovery pass, provider/account capabilities are assessed locally. Unsupported or unverified authoritative lookup escalates the booking to `MANUAL_REVIEW` with a stable reason. The deduplicated task remains `PAUSED`; no provider lookup or repeated creation call occurs.

Recovery commits the task first and applies the booking transition through a booking-owned, version-fenced contract. An interruption between writes resumes after reopening the database, including when the task already exists. Manual-review bookings leave the unresolved partial index. Concurrent confirmation cannot be downgraded. Valid evidence returned by the original in-flight creation may still confirm the same booking; a paused task can remain as historical evidence after this race and is never automatically executed.

The API exposes `MANUAL_REVIEW`; same-key replay returns HTTP 200 without `Retry-After`. The browser displays the booking reference and support guidance, hides retry/recreation controls for that operation and stops polling. Refresh preserves the state and may make one current-status read.

## Validation

- `./mvnw clean test`: **134 passed**, zero failures, errors or skips.
- Backend suite includes 33 workflow API cases, six database-reopen/race cases, three capability-policy cases, 25 aggregate cases and the remaining existing regressions.
- Capability tests verify provider/account scope, unsupported and unverified lookup, and that idempotency metadata alone never authorizes another POST.
- Local HTTP tests verify timeout/lost-response escalation, stable reason codes, replay and competing-key behavior, zero lookup calls, and one original creation call.
- SQLite tests verify recovery after a task-commit/booking-write interruption, task deduplication, discovery exclusion and confirmation winning the CAS race. A valid delayed creation response also converges after manual escalation.
- `npm run test:browser`: **one journey passed**, extended to verify manual-review guidance, refresh persistence, hidden retry controls and cessation of status requests, while retaining earlier happy/error/label paths.

## Limits

No new table, migration, process, resolution endpoint or automatic creation retry was added. Existing formatting was preserved; edits are limited to behavior, tests and documentation. Automated checks use local providers and temporary databases; no chargeable sandbox shipment was created.

Manual review is unresolved, not rejection or proof of non-creation. Verification must occur outside this application. Advertising a future supported lookup capability does not implement a lookup workflow; this implementation safely escalates to `AUTOMATIC_RECOVERY_UNAVAILABLE` until such a workflow is separately delivered. No production failover, load or sharding validation is claimed.

## Frontend integration review

Merged into the other agent's optimized, formatted frontend rather than replacing it with the earlier compact assets. Preserved layout, styling, unit controls, loading indicators and review details. Corrected copy that could imply provider-side idempotency, and ensured interactive step navigation cannot re-enable pending/retry controls after manual review. The browser journey passed against this merged frontend, including a step-navigation check while in manual review.
