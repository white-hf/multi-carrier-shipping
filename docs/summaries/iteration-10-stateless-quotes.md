# I10 — Stateless quotes and provider-quote booking identity

## Delivered behavior

- Quotes validate, invoke the existing bounded provider port and return transient normalized offers. No QuoteStore, internal rate-request/quote identity, stored execution lifecycle or quote-status endpoint remains. Neither quotes nor bookings require Idempotency-Key.
- Booking accepts providerId/providerQuoteId plus submitted shipping/display snapshots. Backend login and configuration determine owner/account. The committed unique insert winner alone dispatches; ordinary existing-identity creation is replay-only, including RETRYABLE.
- Read-only booking status and provider-quote lookup are separate from explicit `POST /api/bookings/{id}/retry`. Retry is limited to proven-unsent RETRYABLE and one state/version claim winner, using the originally saved facts.
- The typed booking snapshot contains all shipping/display inputs without depending on quote storage. Provider receipt/label facts are separate. Actual price/service columns remain null because the current verified adapter response has no such facts; browser prices are never promoted to confirmed amounts.
- Browser storage v2 retains account-scoped active operations before dispatch, including multiple unresolved query entries. Reload/status checks never send creation. Stale input/operation responses cannot replace newer UI state. Legacy bookingId can still be read; legacy internal quoteId is never treated as providerQuoteId.
- Existing account isolation, logout cleanup, polling bounds, manual-review guidance and label view/download/print/email/share behaviors remain.

## Latest user decision and database boundary

The user explicitly removed historical database migration/old-table compatibility from scope. The revised V001 creates only two business tables with `UNIQUE(user_id, provider_id, provider_quote_id)`. After a reported local JAR startup failure, the user authorized explicit old-database deletion or deployment-script support. The launcher now provides `--reset-database` / `--reset-database-only`, refuses a file held by a running process when lsof is available, and honors an explicit database-path override over `.env`. Normal startup still never silently resets data.

The initially reported passing backend/browser tests used a fresh temporary database and did **not** prove an existing local database could start the packaged JAR. The reproduced failure was a V001 checksum mismatch. `python3 tests/system/packaged-startup.py` closes this verification gap by invoking the actual build/launcher/JAR, checking explicit configured-database deletion and override precedence, then exercising login/rates/create/replay/read-only recovery/PDF label against a loopback provider. Its report includes the JAR SHA-256 and carrier-call counts under `target/packaged-startup/`. This is separate from real-provider acceptance.

Current setup has one configured account per provider. Provider quote uniqueness scope, reuse period and precise ID/creation contract still need deployed-provider confirmation before release. If IDs are account-unique only, the account-scoped index and routing decision must be completed before that deployment. No real carrier writes, publication or release were performed.

## Evidence and verification

Portable checks for this change are `./mvnw clean test`, `npm ci` / `npm run browser:install` followed by `./mvnw -Pbrowser-tests test`, and `python3 tests/system/packaged-startup.py`. The local I9 engineering-harness work is a separate, uncommitted workstream: where installed, `./scripts/verify.sh all` additionally binds checks and artifacts to a worktree digest under `target/verification/<run-id>/report.json`. Its counts include those local harness tests, so they must not be presented as the isolated I10 PR's test count. Use the exact report/commands cited in the final handoff; historical totals do not verify later source changes.

Acceptance evidence includes:

- StatelessQuoteTest: repeated provider reads, exact identity/amount, dates and failures without persistence dependencies.
- ShipmentAdmissionRetryTest: real SQLite concurrent insertion, identity/case/user/provider scope, immutable replay snapshots, no dispatch on storage failure, replay-only RETRYABLE, single-winner retry CAS, unknown-state sending prohibition and expired-booking reads.
- ShipmentRestartTest: close/reopen SQLite around crash-before-send, accepted/lost result, failed confirmation save and confirmed receipt/label recovery; never redispatch an existing identity.
- ShippingApiTest: real application/HTTP provider boundary, stateless rate tables, input/errors, lookup/replay, ownership, rejection/malformed receipts, manual escalation and labels.
- Browser journey: account login, validation/drafts, stale rates, expiry/rejection, unresolved retention, lost create response and read-only lookup, no-record explicit initial retry, RETRYABLE controls and stale GET ordering, labels, storage upgrade and account switch/logout. Two sequential trace segments use the same account/context so retained unresolved operations can be exercised.

The RETRYABLE browser case uses route fixtures for UI sequencing; real database/single-sender guarantees come from backend provider-port/SQLite tests. Other browser creation/recovery scenarios invoke the real test application and simulated carrier with call-count assertions. No simulation proves the real sandbox was exercised.

## Replaced checks and retained failures

Old persisted-rate execution/key/fingerprint tests were replaced by stateless-rate and provider-identity tests rather than kept as requirements for removed behavior. Existing domain, auth, provider protocol and infrastructure suites remain. The separate local architecture harness uses a test-only published interface rather than retaining a dead production QuoteSelectionPort.

During implementation, verification exposed a test Label constructor argument mismatch and long declarations; these were corrected. Browser evidence initially failed on aborted-response network-idle waiting and a missing second trace. Readiness now asserts recovered UI state, and both trace segments are retained. Earlier failures remain under `target/verification/20261116T232434Z-e0649fd3`, `20261117T022144Z-3fd59385` and `20261117T022347Z-811f1283`; they are not passing evidence.

## Explicit limitations

No historical database import, provider-quote ownership proof, quote signing, cross-quote duplicate prevention, general task framework, production load rerun or real-provider acceptance is claimed. Local storage can be cleared and is intentionally removed on logout; it is not a durable server-side history feature.

## PR review follow-up

- Starting a new shipment now persists a null active pointer, while retaining unresolved operations in the query list. Reload remains on the new form after leaving confirmed, rejected, retryable or manual-review work. The browser regression failed on the original rejected-booking restoration before this fix.
- A first initial-create response explicitly rejected before claim (validated 400 input/date/provider codes or 409 QUOTE_EXPIRED, without a booking locator) returns to the form and discards only that unsubmitted selection. A persisted `sendAttempted` marker distinguishes this from later attempts after a lost response; absent markers on older records are conservative. A later admission error or expired RETRYABLE quote does not erase the original recovery identity. Selecting an already-retained identity opens its existing operation instead of replacing it with a new one. Real backend date/expiry/validation cases reproduce the former UNKNOWN-state bug and now assert zero provider creates.
- Packaged-test passwords and signing secrets are generated at runtime rather than hardcoded. GitGuardian's original finding concerned synthetic loopback-test credentials, not a real provider account; no scanner suppression or history rewrite is required by this change.
- Browser acceptance now checks actual stylesheet response status/media type and workspace/sidebar computed styles. A separate injected CSS 404 must be rejected by the gate. Packaged acceptance fetches every linked stylesheet from the running JAR and checks nonempty CSS and application content. Previously passing interaction-only checks did not establish stylesheet availability. A transient/deployment-specific reported 404 is not considered reproduced merely because current origin probes return 200.

Failure evidence preceding the review fixes is retained under `target/verification/20261117T041532Z-96804271` (reset pointer) and `20261117T042447Z-6e1d1678` (first admission failure). Use the final current-source report from the handoff for passing evidence; remote review/check results remain attached to their specific GitHub commit.
