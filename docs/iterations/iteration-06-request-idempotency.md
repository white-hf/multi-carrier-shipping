# Iteration 06 — Durable requests and recoverable quotation attempts

Status: **Proposed — review required before implementation.**
Date: 2026-10-23
Dependencies: complete authentication integration and I5 label integration first. No implementation work is authorized by this document.

## Outcome

One logical shipment request keeps its identity across submission retries, quote failures and browser refreshes. Obtaining fresh rates creates a new quotation attempt against that request, rather than another shipment request. Booking's existing one-booking-per-request constraint then covers all quotation attempts for that same request.

Identical details do not automatically mean the same shipment: two intentionally separate requests may legitimately have identical contents. Idempotency follows the operation key, not global payload matching.

## Proposed flow and APIs

```text
Save immutable shipment request once
    -> obtain rates: attempt A
    -> lost response: replay/read attempt A
    -> failed/expired rates: explicitly start attempt B on the same request
    -> select a quote from the current successful attempt
    -> existing booking API
```

| API | Responsibility |
| --- | --- |
| `POST /api/shipment-requests` | Save the form with required `Idempotency-Key`; return the request identity. Same user/key/content replays it; changed content conflicts. |
| `GET /api/shipment-requests/{requestId}` | Read the owned snapshot and current attempt summary; never call the provider. |
| `POST /api/shipment-requests/{requestId}/quote-attempts` | Start or replay an attempt using its own required `Idempotency-Key`. A refresh or explicit retry after failure uses a new attempt key. |
| `GET /api/shipment-requests/{requestId}/quote-attempts/{attemptId}` | Read persisted progress, offers or failure details; never initiate another provider call. |

The current `POST /api/quotes` form-submission contract is replaced. No compatibility endpoint is required for this project. `POST /api/bookings` and label endpoints remain unchanged.

Request creation returns 201, or 200 on replay. A new attempt can retain the current synchronous provider wait: no queue, background rate worker or async job infrastructure is needed. Concurrent replay returns 202 while it is running and 200 once its persisted outcome is available. Provider failures may initially return 502/503/504, with recoverable operation identity; a subsequent status read/replay returns the persisted failed state without a provider call. The frontend must inspect the state, not equate HTTP 200 with successful rates.

## Main code changes

| Area | Change |
| --- | --- |
| Request domain/application | Introduce an immutable shipment-request lifecycle and application service. Normalize input, freeze server defaults on first save, fingerprint content, fast-read existing key and enforce database uniqueness before returning the snapshot. |
| Quote application | Split `QuoteApplicationService.execute`: it no longer inserts a new form snapshot for every provider call. Resolve the existing owned request, claim a quotation attempt, call the provider outside a transaction, then commit the attempt result. |
| Provider integration | Reuse `SingleProviderRateEngine`, rate timeouts, circuit breaker and bulkhead. No carrier API contract change or automatic provider retry is needed. |
| Persistence | Replace `QuoteStore.begin/complete/fail` with explicit request claim/read and attempt claim/complete/fail operations. Finalization returns an applied/result outcome rather than silently ignoring a failed conditional update. |
| Booking contract | Keep `QuoteSelectionPort` as the only quote dependency. Resolve only owned, unexpired quotes from the request's current successful attempt. A later attempt supersedes previous selection; booking uniqueness stays `(user_id, request_id)`. |
| Frontend | Persist request key before saving, then request ID and attempt key/ID. Recover with the same keys after a lost response or reload. Offer explicit retry/refresh on the same request; edits create a new request/key and clear selection. |
| Error handling | Include `shipmentRequestId`, `quoteAttemptId` and a status location when a claim was persisted. Distinguish these from the existing diagnostic error `requestId`. Never return another user's operation. |

Keep request and quote-attempt responsibilities in the existing quote module initially, with domain types and separate application responsibilities. Do not add another deployable service or couple quote recovery to booking reconciliation.

## Data changes — one additional table

| Table | Proposed change |
| --- | --- |
| `shipment_requests` | Keep the address/package snapshot; add request idempotency key, content fingerprint, current attempt ID and version. Its existing state reflects only the current attempt (initially CREATED). |
| `quote_attempts` — new | Store user/request/attempt IDs, attempt key, state, deadline, bounded error code, timestamps and version. States: RATING, READY, NO_RATES, FAILED. |
| `shipment_quotes` | Add attempt ID; each offer belongs to exactly one request and attempt. Keep price, provider token and expiry immutable. |
| `shipment_bookings` | No new idempotency table or duplicate shipment copy. Preserve the existing request/quote identities and uniqueness. |
| `booking_reconciliation_tasks` | Unchanged; it remains responsible for uncertain chargeable creation, not rate querying. |

The demo moves from four application tables to five. An extra small attempt row is written, but repeated quotations no longer duplicate the full address/package snapshot. Every new row carries `user_id`, preserving future user-based co-location.

Required indexes/constraints:

- Request uniqueness: `(user_id, request_idempotency_key)`.
- Attempt uniqueness: `(user_id, request_id, attempt_key)`.
- One running attempt per request: partial unique `(user_id, request_id)` where state = RATING in SQLite, backed by short claim transactions.
- Owned attempt reads: `(user_id, request_id, attempt_id)`; quote reads/joins use owned request/attempt identity. Validate actual query plans before acceptance.

If another key races with a running attempt, return `409 RATE_IN_PROGRESS` and that owned attempt's status location. The rejected attempt was not accepted; the frontend monitors the winner rather than submitting again. The same-key replay always resolves to its original attempt.

Use a new versioned migration; do not edit applied V001/V002 or delete local data. Backfill a historical attempt for existing requests/quotes, preserve booking references, and reserve server-generated legacy keys for migrated snapshots. Test migration with existing bookings. Final SQL and backfill mechanics are implementation details to verify before applying the migration.

## Failure and concurrency rules

- Provider failure marks the attempt FAILED; successful empty results are NO_RATES. Both remain inspectable.
- Commit offers and successful final state atomically. A failed database commit must not return a successful rates response.
- Persist a deadline plus storage grace period. Recover overdue RATING rows lazily during status/retry handling using a conditional state/version update; no new scheduler is proposed. This is expiry bookkeeping, never a provider query.
- If storage is unavailable, report STORE_UNAVAILABLE with known operation identity. Later reads can expire an abandoned attempt and allow an explicit new attempt.
- Fence finalization so an expired/superseded attempt cannot publish stale offers or overwrite the current request state. Discard an in-flight result that lost its claim.
- Once a new attempt is accepted, clear the old selection. A failed refresh must not silently restore old offers as if they were fresh. Same-key replay of an expired successful attempt remains the same result; new rates require a new attempt key.
- Edits represent a new immutable request. This does not prove a previous uncertain shipment was not created; retain the existing manual-review guidance and never offer a new request as a retry workaround.
- Authentication must retain a stable owner across token renewal for replay and recovery to work. Reuse its user-context interface; do not implement another identity system.

## Acceptance and review decisions

One vertical iteration with four checklist items: ST-601 request idempotency; ST-602 quotation attempts/status; ST-603 frontend recovery and selection rules; ST-604 migration/concurrency/failure/browser acceptance.

Verify concurrent same-key request creation, changed-payload conflict, concurrent attempt claims, failure followed by explicit retry with the same request ID, lost successful response recovery without another provider call, restart/stale-attempt expiry, atomic persistence failure, late-result fencing, owner isolation and quote expiry. Verify that quotes from different attempts of the same request still permit only one booking. Include both normal and exceptional browser journeys.

Before development, review: the four API contracts; one new attempt table; immutable edits creating a new request; superseding old quotes when a new attempt begins; and lazy expiry instead of a new background worker. No automatic creation retry, new login feature, message queue, cache or manual-resolution endpoint is included.
