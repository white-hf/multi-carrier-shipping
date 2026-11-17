# I10 — Stateless quotes and provider-quote booking deduplication

**Date:** 2026-11-16

**Status:** Implementation authorized and in progress. On 2026-11-16 the user removed historical-data/old-table migration from scope and authorized a new database baseline. Verification results are recorded separately; release remains a separate action.

**Requirements:** FR-02–04, FR-06–07, F12–13 in the [PRD](../prd/shipping-workflow.md). See the [target API](../design/application-api.md), [backend design](../design/backend-design.md) and [data dictionary](../design/data-dictionary.md).

## 1. Requirement, simplest solution and trade-offs

Quotes serve display and selection, not a durable application workflow. Validate input → call the provider → normalize → return. Persist business records only when the user explicitly submits a booking.

Use existing booking storage, unique constraints, state/version CAS, provider ports, HTTP guards and manual-review discovery. Remove quote persistence and booking's local quote-resolution dependency. No replacement generic task system, submission-identity system or signed quote credential is needed.

Decisions:

- No application-user-to-provider-quote ownership proof is implemented. Authentication still isolates bookings and labels, but does not prove that a submitted quote originated from that user's rate query.
- Client inputs and displayed prices are submitted snapshots, not trusted provider facts. Basic validation and configured provider admission precede provider validation.
- At most one booking per user/provider/provider quote identity. Distinct quotes may create distinct bookings, even for identical shipping inputs; cross-quote duplicate creation is accepted.
- No historical quote-list recovery, durable quote execution, or same-key quote retry. Refresh/re-query when needed.
- Unknown creation outcomes never authorize another create call. Local uniqueness is not a provider exactly-once guarantee.

Removing quote storage loses server-owned quote/input association, quote history and execution recovery. These losses are intentional, not gaps to fill with signatures, hashes or another persistence layer.

## 2. Provider contract gates — unresolved until verified

Repository evidence: `SandboxRateOperation` maps `RateItem.quoteId()` to a provider quote ID; `SandboxCreateOperation` currently sends quote-only creation using `CreateShipmentRequest(providerQuoteId)`. Current expiry is derived from configured lifetime, not proof of a provider-issued timestamp. This establishes the current adapter shape, not a deployed provider guarantee.

Before implementation finalization/release, record non-secret contract evidence for:

| Question | Required decision |
| --- | --- |
| Is a quote ID unique provider-wide or only within an account? | Default key is `(user_id, provider_id, provider_quote_id)`; if only account-scoped, include a stable backend account identifier in uniqueness, lookup and routing before release |
| Can IDs be reused, and over what period? | Assess against booking/deduplication retention; incompatible reuse blocks release pending a business decision, not an invented generation token |
| Are IDs case-sensitive; what alphabet and maximum length are supported? | Preserve provider-defined identity exactly; do not lowercase, truncate or silently trim/normalize it; reject invalid/oversized values according to verified rules |
| What is required to create from a quote? | Verify quote-only mode and any required shipping fields; map only provider-required fields, not arbitrary browser data |
| What expiry and price guarantees exist? | Distinguish provider expiry from local estimates; do not promise that a client price or local TTL binds provider billing |
| What constitutes rejection or a valid receipt? | Preserve typed rejection/known-unsent/unknown outcomes and existing receipt validation; HTTP success alone is insufficient |

Backend chooses and persists provider account routing. Browser cannot select credentials/account aliases. Account alias changes must not bypass deduplication; if account-scoped identity is needed, settle stable account mapping and historical migration first. No real carrier write call is authorized by this plan.

## 3. Stateless quote flow

1. Authenticate and apply existing API/resource protections; validate sender, recipient, parcel and shipping date.
2. Invoke the existing rate engine/provider adapter with bounded concurrency and external-call deadlines.
3. Normalize actual offers to `providerId`, `providerQuoteId`, carrier/service, money, estimates and available expiry information.
4. Return `200` with `quotes[]`, including an empty list for no rates; failures return normal classified errors.

No business-table writes on success, empty results, validation failures, provider failures, admission rejection or timeout. Logs/metrics remain permitted without sensitive request-body logging. Do not create a quote task for an uncertain read.

Remove rate `Idempotency-Key`, internal quote/request IDs, quote execution claims/versions/deadlines/takeovers, persistence-failure recovery and `GET /api/quotes/{requestId}`. Retain transport timeouts, admission, circuit protection, response bounds, validation and observability. Correlation `X-Request-Id` remains diagnostic, not a persisted rate operation ID.

## 4. Booking creation and concurrency

The API accepts provider identity plus the shipping-input/display snapshot; it accepts neither internal `quoteId` nor `Idempotency-Key`. Resolve `user_id` from login and provider/account from backend configuration.

1. Validate identity format and provider allowlist so lookup is safe.
2. Read the current user's booking by provider quote identity. If present, return it without validating new snapshot semantics, overwriting saved facts or dispatching. Basic JSON/size/identity checks still apply.
3. If absent, validate the new submission snapshot and any existing basic admission rules. Do not verify a persisted quote, require a signature or claim user ownership of the provider quote.
4. Insert a `PENDING` booking with immutable identity, backend account, all necessary shipping/display facts, deadline and version; commit a short transaction.
5. Only the successful insert caller may invoke provider creation. Database failure means no provider call. A unique-conflict loser reads and returns the committed winning booking; it never dispatches or replaces the winner's snapshot. If the winner cannot be read, report storage failure rather than treating it as absent.
6. Map confirmed, definitively rejected, proven-not-dispatched and unknown outcomes, persisting via existing state/version fences outside the network call.

The first SELECT is only an optimization. Enforce `UNIQUE(user_id, provider_id, provider_quote_id)` in the database, adjusted for verified account scope if needed. Different providers' same quote ID do not collide. Different quotes do not share a request-level exclusion.

Submitted amount/service/address facts remain immutable user-submitted snapshots. Provider-confirmed amount/currency/service, when returned, occupy distinct nullable fields; never backfill them from browser values. Preserve original snapshots on all replays and retries. A rejection or malformed result must not produce a success UI.

## 5. Read-only recovery versus explicit sending

| Action | Behavior |
| --- | --- |
| Known booking ID | `GET /api/bookings/{bookingId}` reads owned state |
| Missing booking ID | `GET /api/bookings/lookup?providerId=...&providerQuoteId=...` reads an owned existing booking |
| Lookup finds no row | Return 404; keep original quote identity and explain that the user may actively retry initial submission; do not call create |
| Repeated ordinary create | Return existing booking only, including RETRYABLE; never acquire a retry claim |
| User chooses retry sending | `POST /api/bookings/{bookingId}/retry`, only for proven-unsent RETRYABLE; no new quote/snapshot/key |

All status/lookup GETs are read-only: they do not create, retry, mark overdue state, register tasks or call provider creation. Existing scheduled unresolved-booking discovery remains separate. A 404 is an observation at read time, not proof an in-flight submit cannot later commit. User retry uses the same original identity and lets database uniqueness arbitrate that race.

Explicit retry uses the original booking's persisted account, quote and snapshot. A short CAS checks owner, booking, RETRYABLE state and version, advances to PENDING, increments version and renews the deadline. Only the winner sends; losers return current state. Preserve existing basic eligibility checks where applicable without `quotes.resolve()`; provider makes the final quote-validity decision.

PENDING, PENDING_CONFIRMATION/PENDING_INQUIRY and MANUAL_REVIEW cannot resend; terminal records do not resend either. Quote expiry never deletes a booking, resets uncertainty, or implies no shipment exists. A crash after retry claim is conservative uncertainty, not RETRYABLE proof. No automatic transport, application, proxy or browser create retry is introduced.

## 6. Frontend and local storage upgrade

- Remove random quote/booking idempotency keys. Match/select offers by the pair `(providerId, providerQuoteId)`, not providerQuoteId alone or an internal quoteId.
- On input change, increment the local request sequence, clear unsubmitted selection/results as appropriate, and ignore all success/error/finally handlers for old input revisions or old sessions. Aborting fetch is optional optimization, not the correctness boundary.
- Persist an active operation **before** sending create. Minimum fields: storage schema version, userId, providerId, providerQuoteId, optional bookingId, status, submission/recovery information and timestamps needed by existing bounded recovery. Store only required fields; no tokens, credentials, PDF bytes or object URLs.
- Use owner/session, business identity and local request sequence to decide whether asynchronous responses may update the active UI. A late response for another retained operation may update only that operation, never replace a newer selection/account's UI.
- With bookingId, recover via status GET; without it, use lookup GET. Refresh, network errors and sign-in recovery never automatically send creation.
- After no-row lookup, keep original identity and, if available, the submitted snapshot; offer user-initiated initial retry. If needed snapshot data is missing, explain the limitation rather than inventing it or silently switching quote identity.
- “Check status” is read-only. Only RETRYABLE has a separate “Retry sending” action. Preserve polling limits, distinguish rejection/unknown/manual review, and keep label access independent of creation.
- Expiry can clear an unsubmitted selection, but not an unresolved active operation. Starting another quote/booking is allowed; retain visible query entries for previous unresolved operations. This is a bounded local operation list, not a new backend history/task system; do not silently evict unresolved entries to meet a storage cap.
- Preserve account isolation. Logout clears local account data and pending callbacks/polling; account switch cannot expose the previous user's operations. Local persistence is recovery convenience, not a guarantee after logout or storage clearing.
- Version the local format. Old entries with bookingId continue using status GET even without provider identity. Never reinterpret old internal quoteId as providerQuoteId. Entries lacking bookingId and a valid provider identity display “This older operation cannot be recovered automatically; do not assume it was not submitted. Contact support.” No migration path automatically creates.
- Remove “original idempotency key”, “change key” and similar instructions from UI, errors, tests and active docs.

## 7. New database baseline and cleanup

The user's subsequent decision supersedes the earlier migration requirement: do not implement historical copying or old-table upgrades. The revised V001 defines only `shipment_bookings` and `booking_reconciliation_tasks`; `schema_migrations` remains metadata. No existing database file is automatically deleted or cleared. An incompatible applied checksum fails startup and requires an explicitly selected fresh database.

Remove QuoteStore/JdbcQuoteStore, quote selection resolution, rate execution recovery/status endpoints and obsolete request/key/fingerprint models. Keep booking-owned typed snapshots, exact provider identity uniqueness, receipt/label facts and existing reconciliation tasks. No general identity/task/signature replacement is introduced. Browser legacy-storage handling remains in scope; old server database compatibility does not.

Use coordinated backend/frontend deployment, not mixed duplicate rules against one live database. This task does not authorize release, database deletion or real provider writes.

## 8. Implementation sequence and deliverables

| Phase | Deliverable / exit condition |
| --- | --- |
| Requirements | Synchronized PRD, API, dictionary/design and this plan |
| Contract confirmation | Recorded provider ID uniqueness/reuse/account/format and required create fields; unresolved incompatible semantics block release |
| Database baseline | New two-business-table schema with self-contained typed booking snapshots; incompatible old databases rejected |
| Joint implementation | Backend claims/lookup/explicit retry, stateless quotes, frontend operation recovery, storage upgrade and matching tests |
| Coordinated cutover | No mixed duplicate rules; fresh baseline selected explicitly; release is not performed by implementation |
| Delivery verification | Current source-bound reports and known limitations, followed by separately authorized release |

## 9. Acceptance matrix (not executed for this target)

| ID | Required evidence |
| --- | --- |
| A01 | Quote success/empty/invalid/timeout/rejected/malformed paths write no business tables and require no Idempotency-Key; removed status API cannot restore an old task |
| A02 | Same user/provider/quote concurrent submissions, including different snapshots and delayed requests, produce at most one booking and only the initial committed insert winner dispatches |
| A03 | Different providers with same quote ID and different quotes behave independently; exact quote identity respects verified case/length/account rules |
| A04 | Database insert/commit failure causes zero provider creates; unique losers cannot overwrite the winner; same-identity existing RETRYABLE create never resends |
| A05 | Provider rejection is not success; malformed/lost responses and failed result save remain unresolved and cannot resend automatically |
| A06 | Lost application response and reload recover by bookingId or provider identity using read-only GETs; no-row recovery retains identity and needs a user action; late initial create is deduplicated |
| A07 | Explicit concurrent RETRYABLE retry has one CAS winner and uses the saved snapshot/account; all other states reject sending; ordinary create remains replay-only |
| A08 | Quote expiry never blocks reading an existing booking or erases PENDING/unknown/manual state; expired new selections require re-query as appropriate |
| A09 | Input edits, late success/error responses, account switch/logout, retained unresolved operations, polling bounds and legacy local-storage upgrade behave correctly |
| A10 | Submitted prices/inputs are not provider-confirmed facts; actual fields remain null unless returned; replays never replace original facts |
| A11 | New baseline contains no quote/request tables or old key/hash references; booking snapshots/status/labels survive database reopen; incompatible checksums fail rather than resetting a database |
| A12 | Label retrieval still uses the existing confirmed shipment; manual review stops polling/sending; no new generic recovery machinery |

Use existing harnesses after implementation: targeted backend/domain/JDBC/concurrency/restart/provider protocol tests and controlled browser journeys. Follow `verify-shiptime` and run `./scripts/verify.sh all` before declaring full verification; style and architecture are required for Java changes. Historical migration fixtures are out of scope under the latest decision. No automated test may create real carrier shipments. Report skipped, absent or blocked checks explicitly; historical passing reports do not verify this new target.
