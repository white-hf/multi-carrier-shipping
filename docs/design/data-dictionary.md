# Shipping database — data dictionary

**Updated:** 2026-11-16. I10 uses a new database baseline; historical-data migration is out of scope by user decision. Incompatible existing databases fail migration checksum verification and are never automatically cleared.

DDL: [V001__shipping_schema.sql](../../src/main/resources/db/shipping/V001__shipping_schema.sql). Only bookings and existing manual-review tasks are business tables. Quotes, quote requests and quote execution state are not stored.

## shipment_bookings — booking owned

| Column | Type / nullability | Meaning |
| --- | --- | --- |
| booking_id | VARCHAR(36), PK, required | Stable application booking ID |
| user_id | VARCHAR(64), required | Authenticated backend owner |
| provider_id | VARCHAR(64), BINARY collation, required | Configured provider identity |
| account_alias | VARCHAR(64), required | Non-secret backend account route; retry uses the saved route |
| provider_quote_id | VARCHAR(255), BINARY collation, required | Exact opaque provider quote identity; no lowercasing/truncation |
| submission_snapshot | TEXT, required | Serialized typed BookingSelection, not raw HTTP request/response JSON; immutable submitted facts |
| quote_expires_at | BIGINT, nullable | Submitted expiry admission hint in epoch milliseconds; absent means unknown |
| provider_actual_total_minor | BIGINT, nullable, nonnegative | Reserved separate confirmed provider amount; current quote-only receipt does not provide this, so remains null |
| provider_actual_currency | CHAR(3), nullable | Actual provider currency only; not copied from display price |
| provider_actual_service_id | VARCHAR(128), nullable | Actual provider service only; not copied from display service |
| status | VARCHAR(32), required | PENDING, RETRYABLE, CONFIRMED, FAILED, PENDING_INQUIRY, MANUAL_REVIEW |
| provider_shipment_id | VARCHAR(255), nullable | Validated confirmed provider receipt |
| tracking_number | VARCHAR(128), nullable | Optional provider tracking number |
| label_url | TEXT, nullable | Cached allowlisted provider label URL; never PDF bytes |
| label_status | VARCHAR(32), required | PENDING, AVAILABLE, UNAVAILABLE |
| error_code | VARCHAR(64), nullable | Bounded application outcome/review reason |
| submission_deadline_at | BIGINT, required | Submission/recovery deadline, epoch milliseconds |
| created_at, updated_at | BIGINT, required | Lifecycle timestamps, epoch milliseconds |
| version | BIGINT, required, default 0, nonnegative | Optimistic concurrency fence |

`submission_snapshot` contains provider/account/quote identity, carrier and service IDs/names, submitted acceptedMinor/currency, nullable expiry and provenance, and full ShippingIntent: sender/recipient contact/address/residential fields, parcel dimensions/weight/units/type and resolved shipping date. Money is exact minor units and measurements preserve decimal semantics. These are user-submitted facts, not ownership proof or guaranteed provider charges. Storing one typed booking snapshot avoids dozens of quote-owned columns or any post-claim quote dependency. It is not used as a raw cached API response.

Uniqueness: `UNIQUE(user_id, provider_id, provider_quote_id)`. Current runtime has one configured account per provider. Provider-wide/account-only uniqueness and ID reuse remain external-contract release gates; multi-account routing is not implemented. Do not infer multi-account safety from the current three-column constraint.

Index: `idx_shipping_unresolved(status, submission_deadline_at, booking_id)` for bounded keyset discovery. Status/lookup enforce user ownership; explicit retry and result persistence also fence by booking/version/allowed state. A first identity SELECT is an optimization, never the concurrency guarantee.

## booking_reconciliation_tasks — reconciliation owned

| Column | Type / nullability | Meaning |
| --- | --- | --- |
| task_id | VARCHAR(36), PK, required | Local task ID |
| user_id | VARCHAR(64), required | Authenticated owner copied from the booking contract |
| booking_id | VARCHAR(36), required | Original booking ID |
| booking_locator | VARCHAR(128), required | Existing logical owner/routing locator |
| provider_id, account_alias | VARCHAR(64), required | Saved non-secret provider/account context |
| customer_reference | VARCHAR(128), required | Stable booking correlation; not guaranteed provider idempotency |
| state | VARCHAR(32), required | PAUSED or WAITING; current manual-review writer creates PAUSED |
| available_at | BIGINT, required | Existing task timing field; not permission to retry creation |
| last_error_code | VARCHAR(64), nullable | Manual-review reason |
| created_at, updated_at | BIGINT, required | Task timestamps |

Uniqueness: `(user_id, booking_id)`. Index: `idx_shipping_tasks_due(state, available_at, task_id)`. No task-framework expansion is part of I10. Normal successful booking and status GETs write no tasks. Task presence alone does not prove a booking remains unresolved after late confirmation.

## schema_migrations — shared metadata

`version TEXT PRIMARY KEY`, `checksum TEXT NOT NULL`; applied script bytes are SHA-256 checked. The new baseline creates two business tables. Reusing an older database with a different applied V001 checksum is rejected; no automatic reset or historical import is implemented.

## State and safety semantics

- PENDING: initial insert or explicit retry claim committed; no other caller may dispatch it.
- RETRYABLE: definite evidence that provider dispatch did not start. Ordinary create only returns it; explicit retry must win a new state/version CAS.
- CONFIRMED: validated receipt saved; label readiness is independent.
- FAILED → API REJECTED: definite provider rejection; never success.
- PENDING_INQUIRY → API PENDING_CONFIRMATION: uncertain outcome; no resend.
- MANUAL_REVIEW: authoritative automatic recovery unavailable; no resend. Valid late confirmation may still resolve it.

Quote expiry never deletes duplicate protection or blocks an existing status read. GET status/identity lookup are strictly read-only. SQLite WAL, synchronous FULL, foreign-key enforcement, bounded busy timeout and short transactions remain; a connection pool does not remove SQLite's single-writer limit. Snapshots contain contact PII: exclude database/WAL/SHM files from Git and never log full snapshots.
