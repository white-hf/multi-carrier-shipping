# Iteration 04 — Make unresolved shipment outcomes actionable

Status: Accepted for the agreed demo scope on 2026-10-23. Implementation and regression verification complete.
Baseline: `2c4712c` — accepted I1–I3 demo implementation.

## Outcome

When shipment creation times out and the provider offers no authoritative lookup or verified safe retry, recovery marks the booking **MANUAL_REVIEW**. The frontend stops polling and explains how to seek verification without creating another shipment.

This is one small follow-up iteration. It extends I2 uncertainty handling and I3 operational clarity; the I1–I3 acceptance report remains historical evidence for the baseline.

## Current gap

Creation uncertainty is persisted as `PENDING_INQUIRY`. Scheduled discovery registers a `PAUSED` recovery task, but the booking remains pending and the status API hides manual review as `PENDING_CONFIRMATION`. There is no automatic remote reconciliation for this provider, so the UI suggests progress that cannot occur.

## Scope and design

### ST-404 — Provider capability and domain meaning

- Represent authoritative shipment lookup and creation idempotency explicitly at the integration boundary. Distinguish supported, unsupported and unverified capabilities; absence of documentation is not proof of support.
- Capabilities belong to the configured provider/account, not a hardcoded carrier name. The sandbox adapter reports lookup unsupported and creation idempotency unverified. This is a local typed result; no speculative HTTP lookup request is sent.
- Recovery depends on its own narrow capability/lookup contract, not the provider creation port. Unsupported lookup is a typed outcome, not a timeout or a generic retryable exception.
- Keep `PENDING -> PENDING_INQUIRY` for ambiguous creation. Add a domain transition to `MANUAL_REVIEW` with `AUTHORITATIVE_LOOKUP_UNSUPPORTED`, rather than misusing the existing `INQUIRY_EXHAUSTED` reason.
- Update restored-state validation to accept the correct manual-review reasons. Manual review is unresolved, not confirmed, rejected, or proof that no shipment exists.
- No provider creation retry is introduced, even if a future adapter advertises support; implementing that policy is outside this iteration.

### ST-405 — Durable recovery decision

```text
Create timeout / lost response
    -> PENDING_INQUIRY
    -> next eligible recovery pass checks capabilities
    -> lookup unsupported and safe retry unverified
    -> task PAUSED + booking MANUAL_REVIEW
```

- Extend the existing scheduled workflow recovery path rather than add another scheduler/process. Once discovered, decide immediately without lookup retries or backoff. Existing eligibility deadlines and discovery interval remain unchanged.
- Reuse `shipment_bookings` and `booking_reconciliation_tasks`; no new table or process. Existing booking status constraints already permit `MANUAL_REVIEW`.
- Keep repositories owned by their modules; perform booking updates through a booking-owned recovery contract, not a reconciliation-to-booking SQL join.
- Register the deduplicated paused task first, then compare-and-set the booking from its recoverable state to manual review. Separate short commits remain intentional; do not introduce a cross-module transaction.
- If interrupted between these writes, the booking remains discoverable. An existing task must not prevent completing the booking transition on the next pass.
- Never overwrite a concurrently confirmed booking. Preserve valid in-flight confirmation evidence; explicitly cover its race with manual-review processing.
- Manual-review bookings leave the unresolved discovery index/scan; paused tasks are not executed by the automatic worker. Keep same-key and same-request duplicate protection after escalation.
- Keep reason/status logging and bounded metrics; do not put account, booking IDs or credentials into metric labels.

### ST-406 — API and frontend guidance

- `GET /api/bookings/{id}` returns `MANUAL_REVIEW` explicitly with the stable reason code and existing booking ID.
- Same-key replay returns the same manual-review booking without provider dispatch. Use HTTP 200 for this stable response, without an automatic `Retry-After` suggestion; it does not mean the shipment was confirmed.
- Frontend handles the new state distinctly, stops status polling and restores the same state after refresh.
- Show: **“We could not confirm whether the provider created this shipment. Do not submit it again. Contact support with booking reference [ID] to verify the shipment before taking further action.”**
- Hide automatic status-retry controls for manual review. Preserve the booking reference and do not offer recreation as a recovery action. A separate new shipment remains a separate user intent, not a workaround for this booking.
- No login, operator dashboard, manual resolution endpoint, cancellation/refund workflow or background label polling is added.

## Acceptance checks

| Scenario | Required evidence |
| --- | --- |
| Timeout or dropped response | Booking first becomes uncertain; existing claim and single outbound creation are retained. |
| Unsupported lookup / unverified idempotency | Recovery reaches manual review with zero provider lookup calls and zero additional creation calls. |
| Repeated recovery passes | One paused task; manual booking is not processed repeatedly. |
| Failure after task registration | Reopening/reconstructing services and running recovery completes the booking transition without a duplicate task. |
| Concurrent valid confirmation | Recovery does not downgrade a confirmed booking or discard valid receipt evidence. |
| Same-key / competing-key requests after escalation | Original booking retained; no provider dispatch. |
| API and browser refresh | Explicit `MANUAL_REVIEW`, booking reference and guidance persist; polling stops. |
| Existing behavior | Confirmed creation, definite rejection, uncertain recovery, delayed labels and legacy simulator regressions remain passing. |

Use unit tests for capability/domain decisions, SQLite tests for CAS and interruption recovery, local HTTP stubs for creation uncertainty, and one browser extension for manual-review guidance/polling cessation. Run the backend suite and separate browser suite after implementation. No chargeable sandbox calls are required.

## Completion and limits

Update the application API, backend design, manual guide and a concise I4 summary with actual results. Add a separately dated I4 section to acceptance evidence; do not rewrite I1–I3 historical counts as if they had always included this behavior.

This iteration improves safe escalation and user communication. It cannot establish the remote result or guarantee eventual resolution without provider evidence. Manual verification takes place outside this application. Sustained load, production sharding and provider-supported automatic reconciliation remain outside scope.

## Execution result

All ST-404–406 items are implemented. `./mvnw clean test` passed 134 backend cases with zero failures, errors or skips. The separate browser suite passed its existing journey plus manual-review transition, reload persistence, hidden retry controls and polling cessation. No external sandbox call, schema migration or formatting sweep was introduced. See [I4 summary](../summaries/iteration-04-manual-review.md) for evidence and limits.
