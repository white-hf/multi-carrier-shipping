# Iteration 06 — Idempotent requests and retryable rates

Date: 2026-10-23
Status: **Accepted for the agreed demo scope.**
Scope: Idempotent `POST /api/quotes`, read-only `GET /api/quotes/{requestId}`, version-fenced rate retries, and 24-hour browser draft recovery.

## Delivered

1. **Additive Schema Migration (`V003__rate_request_idempotency.sql`):**
   - Added `idempotency_key`, `content_fingerprint`, `rate_version`, and `rate_deadline_at` to `shipment_requests`.
   - Created `UNIQUE INDEX uk_shipment_request_key ON shipment_requests(user_id, idempotency_key)` and `INDEX idx_request_quotes ON shipment_quotes(user_id, request_id)`.
   - Preserved the four-table application model (`shipment_requests`, `shipment_quotes`, `shipment_bookings`, `booking_reconciliation_tasks`) without adding a `quote_attempts` table or `last_error_code` column.

2. **Canonical Fingerprinting & Version-Fenced Rate Execution (`RateRequest`, `ShippingIntentFingerprint`, `QuoteApplicationService`, `JdbcQuoteStore`):**
   - Length-prefixed canonical SHA-256 fingerprinting normalizes whitespace, postal codes, and `BigDecimal` scales (`5` vs `5.000`).
   - First rate execution returns `201 Created` with `Location: /api/quotes/{requestId}`; same-key replay returns `200 OK`; concurrent in-flight execution returns `202 Accepted` (`Retry-After: 2`).
   - Failed (`RATE_FAILED`), overdue (`RATING` past `rate_deadline_at`), or expired (`QUOTES_EXPIRED`) requests can be re-claimed under the same key by incrementing `rate_version` via compare-and-set; stale completions are discarded.
   - `GET /api/quotes/{requestId}` inspects owned state and transitions overdue `RATING` claims to `RATE_FAILED` without invoking the provider.
   - Error responses for an existing request include `operation.id` and `operation.statusUrl`.

3. **Frontend Draft & Idempotent Rate Retry (`static/js/app.js`):**
   - Persists a 24-hour form draft and active rate operation (`key`, `signature`, `requestId`) in `localStorage` (`shipping.rateDraft.v1`), cleared on `New shipment` or expiry.
   - Retains the same `Idempotency-Key` across failed rate attempts and page reloads; generates a fresh key whenever form inputs are edited.
   - Polls `GET /api/quotes/{requestId}` with bounded retries when `202 RATING` is returned, while preserving 2-hour Guest Token + Fingerprint headers and I5 shipping-label actions.

## Architectural Trade-offs Recorded

- **Backend `shipment_requests` persistence:** Enables customer attribution, auditability, and status recovery independent of third-party provider availability, at the cost of PII storage, retention management, and schema maintenance.
- **Browser `localStorage` draft:** Improves reload and navigation UX, but is ephemeral client state subject to clearing, 24-hour expiration, and shared-device visibility.

## Verification

- `./mvnw test`: **145 passed**, zero failures, errors, or skips (including 9 cases in `RateRequestWorkflowTest` and 35 cases in `WorkflowApiTest`).
- `./mvnw test -Pbrowser-tests`: **1 passed**, zero failures, verifying 2-hour Guest Token issuance, 24-hour draft restoration across reload, retry under the same `key` and `requestId`, edit invalidation after `NO_RATES`, manual review escalation, and I5 shipping-label actions.
