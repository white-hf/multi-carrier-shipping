# Iteration 02 — Implementation record

Status: Verified via automated API/SQLite concurrency tests and Playwright E2E browser journey against a controlled local HTTP provider stub.

Booking now accepts only an internal quote ID and required idempotency key. It resolves the owned selection, persists immutable provider/account/quote/service/amount facts, commits one user-scoped claim and sends quote-only creation outside any database transaction. A second key cannot book the same request again. Matching retries return saved state without another external creation. Provider server faults and invalid/lost responses remain pending confirmation.

Confirmation no longer requires tracking or a ready label. The frontend distinguishes rejection from uncertainty and retains the original operation identity. Label access is an on-demand read of the existing shipment: one frontend attempt, followed by manual retry; no background label scheduler is introduced.

The supplied provider does not establish authoritative reference lookup. Unresolved or abandoned submissions are discovered through a booking-owned contract and registered as paused manual-review tasks in the independently owned recovery table. Bounded keyset scans avoid starving rows beyond the first batch. No undocumented inquiry, automatic resubmission, void or refund is enabled. The earlier simulator's inquiry/lease flow remains in its separate configuration.

Moved existing recovery contracts under booking ownership and local deployment wiring into module/shared configuration. Added typed observer ports, transport instrumentation reuse, correlation IDs, owner checks and same-origin write checks. Provider credentials remain backend-only and redacted from configuration rendering.

Verified in `WorkflowApiTest`: concurrent same-key claim races and concurrent different-key/same-request races (exactly 1 outbound provider submission), `IDEMPOTENCY_CONFLICT` on changed quote, `QUOTE_EXPIRED` and `QUOTE_NOT_BOOKABLE` rejection, `422 REJECTED` and `200 REJECTED` replay, `202 PENDING_CONFIRMATION` on upstream 5xx, late confirmation convergence after discovery, paused manual-review task deduplication, and delayed label retrieval (202 pending -> manual retry -> 200 PDF and 303 allowlisted HTTPS redirect) without recreating shipments.

Independent acceptance follow-up (2026-10-23): **Accepted for the agreed demo scope**. Provider shipment IDs now require valid positive numeric identity before confirmation. Added malformed success, actual HTTP timeout, dropped-response and workflow database-reopen tests, including interruption after claim and failure to save a successful provider result. Replays and competing keys never dispatch again; overdue cases retain one paused manual-review task. See [acceptance report](acceptance-test-report.md) for evidence and restart/lookup limitations.
