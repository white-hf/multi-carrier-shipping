# Iteration 07 — RDBMS-agnostic schema and portable JDBC persistence

**Status:** Accepted for the agreed demo scope on 2026-10-23  
**Date:** 2026-10-23  
**Requirements:** FR-01–07, F12–13; non-functional portability, explicit column projection and write-safety invariants.

---

## 1. Objective & Outcome

Decouple the four workflow tables (`shipment_requests`, `shipment_quotes`, `shipment_bookings`, `booking_reconciliation_tasks`) and their JDBC stores from SQLite-specific SQL syntax and untyped columns. Establish a **100% RDBMS-agnostic (ANSI SQL + standard Spring JDBC)** persistence baseline that can run on SQLite, PostgreSQL, MySQL, or any standard relational database without altering business workflows or adding a fifth table.

---

## 2. Core Design Rules (RDBMS-Agnostic Baseline)

| Concern | Portable Standard | Rationale |
| --- | --- | --- |
| **UUIDv7 Keys** | `VARCHAR(36)` | Lexicographically time-ordered across all B-Tree engines; 100% compatible with standard JDBC `getString`/`setString` without dialect-specific `BINARY(16)` or `UUID` bindings |
| **SHA-256 Fingerprints** | `CHAR(64)` | Lowercase hex digest; avoids `BYTEA` vs `BINARY(32)` vs `BLOB` dialect differences |
| **Idempotency Keys** | `VARCHAR(64)` + Java exact `equals` | Avoids `VARBINARY` binding friction while protecting against case-insensitive default collations in some databases |
| **Timestamps & Money** | `BIGINT` | UTC epoch milliseconds and minor currency units (`cents`); immune to session timezone drift and binary float errors |
| **Active Rate Deadline** | `BIGINT NULL` | `NULL` when not in `RATING` (replaces sentinel `0`) |
| **Parcel Measurements** | `DECIMAL(12, 4)` + `@Digits(integer = 8, fraction = 4)` | Rejects >4 decimal places at API validation so no database silently rounds values after fingerprinting |
| **Boolean Flags** | `SMALLINT NOT NULL CHECK (... IN (0, 1))` | Universal across all RDBMS engines; replaces `"1"`/`"0"` text strings |
| **Unique Conflict Handling** | Standard `INSERT INTO` + catch `DuplicateKeyException` | Removes dialect-specific `ON CONFLICT DO NOTHING` and avoids `INSERT IGNORE` (which swallows non-uniqueness errors) |
| **Keyset Pagination** | Standard `(deadline > ? OR (deadline = ? AND id > ?))` + portable composite indexes | Removes row-value tuple comparison `(a, b) > (?, ?)` and partial `WHERE` index clauses not supported by all RDBMS engines |

---

## 3. Task Checklist

| Task ID | Scope | Deliverable & Verification |
| --- | --- | --- |
| **ST-701** | **Portable Schema Migration (`V004__portable_schema_baseline.sql`)** | Rebuild the 4 workflow tables using standard ANSI SQL types (`VARCHAR(N)`, `CHAR(N)`, `BIGINT`, `DECIMAL(12,4)`, `SMALLINT`), rename `length/width/height/weight` to `parcel_*`, make `rate_deadline_at` nullable, simplify recovery uniqueness to `(user_id, booking_id)`, drop redundant `UNIQUE(user_id, request_id, quote_id)`, and replace partial indexes with portable composite indexes. Preserve all existing rows during migration. |
| **ST-702** | **Dialect-Free JDBC Stores (`JdbcQuoteStore`, `JdbcShipmentStore`, `JdbcPendingTaskStore`)** | Replace `ON CONFLICT ... DO NOTHING` with isolated standard `INSERT` + `DuplicateKeyException` handling; replace tuple `(a, b) > (?, ?)` with portable ANSI disjunction; verify exact case-sensitive `idempotency_key` matching in Java; bind `residential` as integer `0`/`1` and inactive `rate_deadline_at` as `NULL`. |
| **ST-703** | **Domain Validation Alignment (`ShippingIntent`, `ShippingAddress`)** | Add `@Digits(integer = 8, fraction = 4)` to parcel dimensions/weight and align `@Size` constraints with column lengths so invalid precision or overlong strings fail fast with `400 INVALID_REQUEST` before reaching JDBC. |
| **ST-704** | **Migration, Concurrency & E2E Acceptance** | Verify `V001 → V002 → V003 → V004` upgrade preserves existing data; verify 4-table invariant and query plans; run all backend concurrency/restart tests and Playwright browser E2E journey. |

---

## 4. Exit Gate & Execution Result

- Zero dialect-specific SQL (`ON CONFLICT`, partial `WHERE` indexes, tuple `>`) or `SELECT *` queries in `workflow` repositories or `V004` schema.
- Legacy rows migrated cleanly through `V004__portable_schema_baseline.sql` with all 4 application tables intact.
- **146 backend tests** (`./mvnw test`) and the **Playwright browser E2E suite** (`./mvnw test -Pbrowser-tests`) passed with zero regressions. See [I7 summary](../summaries/iteration-07-portable-persistence.md).
