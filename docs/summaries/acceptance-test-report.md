# Shipping workflow — acceptance report

> Scope clarification (2026-10-29): the reproducible backend/browser commands below use local provider stubs. They do not establish fresh browser-to-real-sandbox acceptance, especially invalid-address validation. The [real sandbox validation plan](../runbooks/sandbox-validation.md) defines the pending layered negative-input cases; no live passes are claimed by this update.

> Schema note: historical migration names below describe development work. The current first-release schema is consolidated in `src/main/resources/db/shipping/V001__shipping_schema.sql`; earlier development migration scripts are no longer shipped.

Date: 2026-10-23  
Status: **Accepted for the agreed demo scope — I1, I2 and I3** after independent review follow-up.

## Reproducible validation

| Command | Result | Scope |
| --- | --- | --- |
| `./mvnw clean test` | 123 passed; zero failures, errors or skips | Backend unit tests, real SQLite persistence, concurrency and local HTTP provider integration |
| `npm ci` followed by `npm run browser:install` | Passed | Lockfile-based Playwright installation and matching Chromium |
| `npm run test:browser` | 1 passed; zero failures, errors or skips | Eight-stage browser journey against the running application and local provider |

The 123 backend cases include 32 `WorkflowApiTest` cases, four `ShipmentRestartTest` cases and 87 existing regression cases. The browser journey is deliberately separate from the default Maven suite; it is not included in the backend count. The previous report's 103 tests described the earlier combined suite.

All current automated checks use synthetic accounts and local provider stubs. They require no sandbox credentials and create no external shipments. See [workflow setup](../runbooks/workflow-setup.md) for prerequisites and exact commands. Maven XML/text results are generated under `target/surefire-reports/`. Archive backend reports before the browser run if retaining both runs: the browser suite replaces the `WorkflowApiTest` report.

## Iteration acceptance

| Iteration | Evidence | Decision |
| --- | --- | --- |
| I1 — Enter details and compare rates | All 13 Canadian regions; imperial, metric and mixed units; validation before provider dispatch; durable sorted quotes; hidden provider quote token; empty/error states; browser sorting, keyboard selection and edit invalidation | Accepted |
| I2 — Create safely and obtain a label | Durable claim before dispatch; same-key and different-key/same-request concurrency; conflicting intent and expired quotes; rejection and uncertain outcomes; independent delayed-label lifecycle; new malformed-success, dropped-response, actual-timeout and restart checks below | Accepted |
| I3 — Verify and prepare delivery | Independent rate/create circuit behavior; ownership and same-origin checks; redacted configuration; metrics and indexed query plans; migration checksums; pinned browser dependencies; documented setup and limitations | Accepted |

## Review findings closed

| Finding | Change and verification |
| --- | --- |
| Boolean or otherwise malformed shipment ID could become confirmed | Adapter requires a positive signed 64-bit shipment identity, encoded as a JSON integer or decimal-digit string. Twelve invalid response variants remain `PENDING_CONFIRMATION`; three valid identity variants confirm. Replay and discovery never create again. |
| Lost response and timeout were not demonstrated for the new workflow | `acceptedShipmentWithLostResponseDoesNotResubmit` consumes the creation request then closes without HTTP headers. `acceptedShipmentWithActualTransportTimeoutDoesNotResubmit` withholds the response beyond the configured 500 ms test deadline. Both retain one provider submission, replay the same booking and register one paused manual-review task. |
| Restart and failed confirmation persistence were not demonstrated for the new workflow | `ShipmentRestartTest` closes/reopens the file-backed workflow database and reconstructs services after interruption following claim, response loss after acceptance, or a failed confirmation write. Both same-key replay and a competing key retain the original booking without dispatch. Discovery deduplicates paused tasks. Confirmed receipts also survive reopening and replay. |
| Browser runner depended on one machine's npm/Chromium cache | Pinned Playwright in `package.json` and `package-lock.json`; checked-in `tests/browser/workflow-journey.cjs`; separate Maven `browser-tests` profile; bounded process wait and captured failures. Verified through the documented dependency/browser installation and execution commands. |
| Delivery status contradicted the acceptance report | Delivery plan, documentation index, iteration summaries and operating instructions now identify the accepted scope and separate current local evidence from historical sandbox observations. |

## Browser journey

The browser acceptance case covers default backend mode; validation and input retention; provider errors and no rates; sorting, keyboard selection and edit invalidation; quote expiry; explicit rejection; uncertain outcome across reload without resubmission; and confirmed creation with one automatic label read followed by manual retry to obtain a PDF. Label retrieval never calls shipment creation.

## Historical external sandbox observations

The preceding implementation report recorded live rate retrieval (18 quotes, approximately 13.7 seconds), quote-only shipment creation (approximately 17.1 seconds), PDF label retrieval (approximately 76 KB), idempotent replay and same-request duplicate rejection. These are historical observations supplied with the implementation, not fresh measurements from this review follow-up. They are not throughput benchmarks or latency guarantees. No additional external shipment was created during closure of the findings.

## Limits and operating posture

- Authoritative lookup by client reference is not established for the configured provider API. Unknown creation outcomes remain unresolved and require manual review; paused tasks never authorize automatic resubmission, cancellation or refund.
- Restart validation reopens SQLite and reconstructs the application services. It does not simulate an operating-system power failure, kill a running JVM, or validate production failover.
- Rate/create circuit separation is tested. Sustained-load behavior, saturation limits, P95/P99 latency and production cluster/shard operation are not established by this acceptance suite.
- SQLite, the configured demo user and local deployment remain the agreed demo posture. There is no production authentication or implemented database sharding.
- Frontend assets need no Node build. Node and Playwright are only required for browser acceptance testing.

## I4 follow-up — 2026-10-23

**Accepted for the agreed demo scope.** Current backend suite: **134 passed**, zero failures/errors/skips (`./mvnw clean test`). The separate browser suite passed one journey, extended with manual-review escalation, refresh persistence, hidden retry controls and polling cessation (`npm run test:browser`).

Recovery now converts unsupported/unverified provider lookup into explicit booking `MANUAL_REVIEW`, retaining a deduplicated paused task. Tests cover task-first interruption recovery, zero provider lookup/retry calls, manual replay and duplicate prevention, and both directions of the concurrent confirmation race. See the [I4 summary](iteration-04-manual-review.md) for detail.

The earlier 123-case count and eight-stage browser account above describe the I1–I3 baseline; they have not been retroactively changed. Manual verification still takes place outside the application, without resolution endpoints or an automatic provider reconciliation capability. No new live sandbox shipment was created during I4.

## Guest Session Token & Client Fingerprint Security — 2026-10-23

**Accepted.** Current backend suite: **135 passed**, zero failures/errors/skips (`./mvnw test`). The separate browser suite passed (`./mvnw test -Pbrowser-tests`).

- **2-Hour Fingerprint-Bound Guest Token (`GuestSessionTokenService` & `WorkflowAuthController`)**: `POST /api/auth/guest` derives a privacy-safe `guest-<hmac>` identity from the client instance seed and signs a 2-hour (`7200s`) HMAC-SHA256 token bound to the client's environment fingerprint hash (`X-Client-Fingerprint`).
- **Unified API Enforcement (`WorkflowRequestFilter` & `WorkflowContextConfiguration`)**: `/api/quotes` and `/api/bookings/**` require a valid Bearer token and matching `X-Client-Fingerprint` (falling back to `SameSite=Strict; HttpOnly` cookies on `GET` requests for direct browser PDF label navigation). Controllers and services consume `UserContext.userId()` uniformly without guest-specific branching.
- **Verification**: `WorkflowApiTest.enforcesGuestTokenAndClientFingerprintAndIsolatesGuestSessions` and `tests/browser/workflow-journey.cjs` verify `401 UNAUTHORIZED` on missing/expired tokens or mismatched fingerprints, deterministic same-client guest ID resolution, 2-hour TTL enforcement, and strict cross-guest resource isolation (`404 RESOURCE_NOT_FOUND`).

## I5 follow-up — 2026-10-23

**Accepted for the agreed demo scope.** Backend regression: **135 passed**, zero failures/errors/skips. The separate browser journey passed label pending/error/ready/demo states, verified downloaded PDF bytes (`shipping-label.pdf` fixture), print-viewer dispatch, email-draft content, native file sharing/cancellation, download fallback, URL-only viewer fallback and reset/reload while retaining 2-hour Guest Token + Fingerprint authentication headers. Label actions never repeated shipment creation. See [I5 summary](iteration-05-label-actions.md) for evidence and native browser/OS limitations. No new backend API or chargeable sandbox creation was introduced.

## I6 follow-up — 2026-10-23

**Accepted for the agreed demo scope.** Backend suite: **145 passed**, zero failures/errors/skips (`./mvnw test`, including 9 cases in `RateRequestWorkflowTest` and 35 cases in `WorkflowApiTest`). The separate browser journey passed (`./mvnw test -Pbrowser-tests`).

- **Idempotent Rate Requests & Retryable Execution (`V003__rate_request_idempotency.sql`, `RateRequest`, `ShippingIntentFingerprint`, `QuoteApplicationService`)**: `POST /api/quotes` requires `Idempotency-Key`, reuses the request on matching canonical content (`201` initial, `200` replay/retry, `202` in-flight), rejects changed content with `409 IDEMPOTENCY_CONFLICT`, exposes read-only `GET /api/quotes/{requestId}`, and fences retries via `rate_version` without adding a fifth table or `last_error_code` column.
- **Browser Draft vs. Durable Business Record**: The frontend retains a 24-hour local draft (`localStorage`) that restores form inputs and the active rate `Idempotency-Key` across reloads, while the backend retains `shipment_requests` as the authoritative customer record. See [I6 summary](iteration-06-request-idempotency.md).

## I7 follow-up — 2026-10-23

**Accepted for the agreed demo scope.** Backend suite: **146 passed**, zero failures/errors/skips (`./mvnw test`, including 10 cases in `RateRequestWorkflowTest` and 35 cases in `WorkflowApiTest`). The separate browser journey passed (`./mvnw test -Pbrowser-tests`).

- **RDBMS-Agnostic ANSI SQL Schema (`V004__portable_schema_baseline.sql`)**: Standardized bounded ANSI SQL types (`VARCHAR(N)`, `CHAR(N)`, `BIGINT`, `DECIMAL(12,4)`, `SMALLINT`), renamed `parcel_*` columns, cleared inactive `rate_deadline_at` to `NULL`, and replaced SQLite partial indexes with portable composite indexes while preserving all existing rows across `V001 → V004`.
- **Explicit Column Projections & Dialect-Free JDBC Stores**: Eliminated all `SELECT *` queries across repositories (including single-column `SELECT state` in `QuoteStore.requestState` during booking quote resolution), replaced `ON CONFLICT DO NOTHING` and tuple `>` with portable ANSI SQL and `UniqueConstraints.isUniqueViolation`, and aligned Web UI, `@Digits(8,4)` / `@Size` validation, and database column constraints for lossless round-trip storage. See [I7 summary](iteration-07-portable-persistence.md).

## Separate load-test evidence

The [load-test report and charts](../performance/load-test-results.md#visual-summary) show dataset growth, concurrency, throughput and latency. They are separate from the functional acceptance results above and describe the historical `78c729e` run. The full load benchmark has not been rerun after the booking admission fix.

## 2026-10-26 — Quote batch persistence regression

The current quote completion path uses JDBC batch insertion within the existing request-state/quote replacement transaction. Added SQLite coverage verifies multiple offers and nullable fields, empty results, and rollback restoring previous offers after a later batch row fails. The combined save-and-rate API remains unchanged; its splitting tradeoff is documented in the service class. The quote lifetime remains unchanged.

- `./mvnw test`: **118 passed**, zero failures/errors/skips.
- `npm run test:browser`: **one Playwright Chromium E2E journey passed**, zero failures/errors/skips, exercising the existing rate, booking and label paths against local provider stubs.
- Backend XML reports retained at `/tmp/shipping-20261026-final-backend-reports/` before the browser run replaced the shared test-class report. Logs: `/tmp/shipping-20261026-final-backend.log` and `/tmp/shipping-20261026-final-e2e.log`. Temporary artifacts may be removed by system cleanup.
- No real sandbox calls, production data changes, frontend implementation changes, MySQL/PostgreSQL checks or full load-test rerun.
