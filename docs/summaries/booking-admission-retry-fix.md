# Booking admission rejection: backend fix

Validated: 2026-10-26. Scope: backend and API contract; frontend files unchanged.

## Result

A full create bulkhead or open create circuit now produces a known-unsent booking, not an unknown external outcome. Creation returns `503 SHIPMENT_SUBMISSION_NOT_DISPATCHED`, `Retry-After: 2`, and an operation/status URL. Status queries expose `RETRYABLE`.

An explicit retry uses the original quote and idempotency key, revalidates quote eligibility, and acquires a durable `RETRYABLE -> PENDING` CAS. Only the winning version may dispatch. Confirmed, uncertain and manual-review bookings never gain retry permission through this path. No automatic provider creation retry was added.

## Verification

| Check | Result |
| --- | --- |
| `./mvnw test` | 115 passed; zero failures, errors or skips |
| `./mvnw test -Pbrowser-tests` | One existing browser journey passed |
| Real bulkhead/circuit rejection through the API | Zero provider creation calls; status RETRYABLE; no recovery task |
| Same-key retry after guard recovery | Original booking confirmed; exactly one provider creation |
| Eight concurrent retries against SQLite | One dispatch winner; stale-version outcome rejected |
| Repeated local refusal / restart | One durable booking retained; retryability preserved |
| Modified payload / competing key / expired quote | No creation dispatched |
| Lost response during retry | Remains uncertain; another same-key request does not redispatch |
| Crash after committed retry claim | Remains unresolved; no unsafe redispatch |
| Consolidated release baseline | Final constraints/indexes created directly; reapplication preserves rows; incompatible development history fails without data deletion |

Tests used temporary SQLite files and local stubs only. No real sandbox requests or demonstration database changes. No MySQL/PostgreSQL validation. The full load suite was not rerun.

Backend XML reports were retained at `/tmp/shipping-baseline-backend-reports/` before the browser suite replaced the same test-class report. Execution logs: `/tmp/shipping-baseline-backend.log` and `/tmp/shipping-baseline-browser.log`. Temporary files may be removed by system cleanup.

## Migration and frontend handoff

`V001__shipping_schema.sql` is now the sole first-release baseline and already includes `RETRYABLE`. Transitional development migrations were removed. Use a fresh `SHIPPING_DATABASE_PATH`; the application does not upgrade, delete or reset old development databases. Repeated startup on the consolidated baseline verifies its checksum and preserves all rows. Historical uncertain records are not imported or reclassified.

Frontend work is separate: recognize `RETRYABLE`, retain the operation ID and original key, and offer an explicit same-key retry after the suggested delay. Do not interpret every 503/network failure as proven-unsent. Unknown/manual-review states must retain their existing no-resubmission behavior. See [API contract](../design/application-api.md#creation-refused-before-dispatch-explicit-retry).
