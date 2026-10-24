# Iteration 07 — RDBMS-agnostic schema and portable JDBC persistence

Date: 2026-10-23
Status: **Accepted for the agreed demo scope.**
Scope: ST-701–704; RDBMS-agnostic ANSI SQL schema (`V004__portable_schema_baseline.sql`), explicit column projections (eliminating `SELECT *`), dialect-free JDBC repositories, and Web/Domain/Database input boundary alignment.

## Delivered

1. **RDBMS-Agnostic ANSI SQL Schema (`V004__portable_schema_baseline.sql`):**
   - Standardized bounded ANSI SQL types across `shipment_requests`, `shipment_quotes`, `shipment_bookings`, and `booking_reconciliation_tasks` (`VARCHAR(36)` UUIDv7 keys, `CHAR(64)` SHA-256 digests, `VARCHAR(64)` user/idempotency keys, `BIGINT` UTC epoch timestamps/minor currency units/versions, `DECIMAL(12, 4)` parcel measurements, `SMALLINT NOT NULL CHECK (IN (0, 1))` residential flags).
   - Renamed `length`, `width`, `height`, `weight` to `parcel_length`, `parcel_width`, `parcel_height`, `parcel_weight` and made `rate_deadline_at` nullable (`NULL` when not `RATING`).
   - Replaced SQLite-specific partial indexes and redundant unique constraints with portable composite indexes (`idx_request_quotes(user_id, request_id)`, `idx_workflow_unresolved(status, submission_deadline_at, booking_id)`, `idx_workflow_tasks_due(state, available_at, task_id)`, and `UNIQUE(user_id, booking_id)` on recovery tasks).

2. **Explicit Column Projections & Dialect-Free JDBC Stores (`JdbcQuoteStore`, `JdbcShipmentStore`, `JdbcPendingTaskStore`, `UniqueConstraints`):**
   - Eliminated all `SELECT *` statements across repositories; added `QuoteStore.requestState(userId, requestId)` (`SELECT state FROM shipment_requests ...`) so quote resolution during booking checks only the 1 required column instead of deserializing 39 columns.
   - Replaced SQLite-specific `ON CONFLICT ... DO NOTHING` and row-value tuple comparisons `(a, b) > (?, ?)` with standard ANSI `INSERT INTO` + `UniqueConstraints.isUniqueViolation(DataAccessException)` and portable disjunction predicates.
   - Enforced case-sensitive Java `equals` verification on `idempotency_key` lookups to guard against case-insensitive default collations across RDBMS engines.

3. **End-to-End Web Input, Domain Validation & Storage Alignment (`index.html`, `app.js`, `ShippingAddress`, `ShippingIntent`):**
   - Aligned HTML `maxlength`/`max`, frontend JS validation, Java Bean Validation (`@Size`, `@Digits(integer = 8, fraction = 4)`), and database column definitions so over-precision decimals (>4 fractional digits) or overlong strings fail fast with `400 INVALID_REQUEST` rather than silent rounding or `503` storage errors.
   - Normalized blank optional address inputs (`companyName`, `addressLine2`, `email`) to `null` and normalized trailing decimal zeros in `ShippingIntent.Package` for lossless round-trip equality.

## Verification

- `./mvnw test`: **146 passed**, zero failures, errors, or skips (including 10 cases in `RateRequestWorkflowTest` and 35 cases in `WorkflowApiTest`).
- `./mvnw test -Pbrowser-tests`: **1 passed**, zero failures, verifying the complete Playwright headless Chromium E2E journey.
