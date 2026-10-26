# Iteration 06 — Idempotent requests and retryable rates

> Schema note: historical migration names below describe development work. The current first-release schema is consolidated in `src/main/resources/db/shipping/V001__shipping_schema.sql`; earlier development migration scripts are no longer shipped.

Status: Accepted for the agreed demo scope on 2026-10-23. This replaces the earlier five-table/quote-attempt proposal.

## Trade-off and scope

- **Backend business record (`shipment_requests`):** Retained independently of third-party providers to support customer attribution, support troubleshooting, and status inspection (`GET /api/quotes/{requestId}`) even when the upstream provider fails. Trade-off: requires storing address/contact PII, managing data retention, and maintaining schema migrations.
- **Browser local draft (`localStorage`):** Restores form inputs and the active rate operation identity across page refreshes and navigation for 24 hours. Trade-off: subject to user clearing, expiration, and shared-device visibility, so it supplements rather than replaces backend persistence.
- **Four application tables preserved:** No `quote_attempts` table or persisted `last_error_code` column is added. Specific upstream errors are logged and returned on the active HTTP response; later status reads expose `RATE_FAILED` and retry guidance.

`POST /api/quotes` requires a user-scoped `Idempotency-Key`. Matching canonical content reuses the request. Changed content with the same key conflicts (`409 IDEMPOTENCY_CONFLICT`); no request-update API is introduced. Failed or abandoned rate execution can be retried with the same key after a version-fenced claim (`rate_version`). A live execution returns `202 Accepted` without another provider call. Valid completed quotes replay from storage; expired quotes (`QUOTES_EXPIRED`) may be refreshed under the same request.

Additive migration `V003__rate_request_idempotency.sql` adds `idempotency_key`, `content_fingerprint`, `rate_version` and `rate_deadline_at` to `shipment_requests` along with `uk_shipment_request_key` and `idx_request_quotes`. Legacy rows remain readable; keys are required for all new writes.

`GET /api/quotes/{requestId}` reads owned progress/results and expires abandoned execution conditionally, never calling the provider. Quotes and state commit atomically; stale execution cannot publish offers.

## Acceptance and verification

Verified by **145 passing backend tests** (`./mvnw test`, including 9 cases in `RateRequestWorkflowTest` and 35 cases in `WorkflowApiTest`) and the **Playwright browser E2E journey** (`./mvnw test -Pbrowser-tests`):
- Canonical same-key replay (`201` -> `200`), missing/invalid key rejection (`400`), and changed-content conflict (`409` with `operation.id` and `operation.statusUrl`).
- Concurrent matching rate requests (1 provider call; followers receive `202 Accepted`).
- Failure and explicit retry under the same key/requestId (`rate_version` incremented, zero `last_error_code` column), overdue deadline reclamation, stale completion fencing, and transactional rollback on quote insert failure.
- Migration preservation of pre-existing `V001`/`V002` rows, four-table invariant, and SQLite index plan verification (`uk_shipment_request_key`, `idx_request_quotes`).
- Browser 24h draft restoration across page reload, retry reusing the original key/requestId, and new key generation when shipment inputs are edited.
