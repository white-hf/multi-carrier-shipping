# I11 — Spring HTTP service interfaces: execution summary

**Date:** 2026-11-17. **Branch:** `codex/spring-http-service-clients`. **Base:** committed I10 state (PR #15 still open; long-lived unrelated local harness work preserved).

## Requirement and outcome

Replaced the project-owned declarative HTTP framework with Spring Framework 6.1 HTTP Service Interfaces while preserving functional and technical behavior: same endpoints, authentication, timeouts, admission/circuits, status semantics, diagnostics, DTO validation and zero automatic write retries.

## What changed

- Added pure native interfaces per provider slice: `SandboxRatesHttpService`, `SandboxShipmentsHttpService`, `SandboxLabelsHttpService`. Slice-local Spring configurations create proxy beans with `HttpServiceProxyFactory`/`WebClientAdapter`, and operation constructors inject them. Spring binds method, JSON body, generic response type and path variables.
- Shipments and label routes use a provider-scoped Spring PropertySourcesPropertyResolver over the normalized bound `shipping.provider.shipments-path` value. The earlier SandboxRoutes string-replacement helper is deleted; blank-to-default and startup validation remain unchanged.
- `OutboundClientRegistry` now provides the shared WebClient (pool, connect/read timeouts, response size, strict provider JSON codecs, no automatic retries) plus a filter enforcing same-origin and traversal safety and recording bounded, redacted response diagnostics via Reactor Context.
- Provider operations keep status interpretation inside `OutboundGuard` admission/circuit/deadline scope: rates 200/404/400/401/429/5xx, create 200/201/400/other-unknown, labels 202/404/PDF/allowlisted JSON. Invalid 2xx bodies remain protocol failures, not successes.
- Removed `OutboundClient`, `OutboundEndpoint`, `StatusPolicy`, `ResponseDecoder` and their imperative `Exchange` API. `OutboundJson` centralizes the provider codec rules without replacing the application ObjectMapper.
- `SandboxProviderConfig` exposes shared transport/account/guard and scoped route configuration; each slice owns its HTTP contract, proxy bean and operation class, so adding an API does not modify central configuration.
- Label JSON decoding now reads already-buffered bytes with the same strict provider ObjectMapper, without DataBuffer/ResolvableType/Jackson decoder plumbing. Null JSON, wrong scalar types, trailing data and disallowed URLs remain errors. Pure receipt/offer interpretation is named separately, but still executes inside the original guard. No generic invocation helper or diagnostic framework is introduced.

## Verification records

Portable checks for this PR are `./mvnw test`, `npm ci` followed by
`./mvnw -Pbrowser-tests test` (install Chromium first when missing), and
`python3 tests/system/packaged-startup.py`. The local `scripts/verify.sh` reports also
exercise the separate, uncommitted I9 engineering harness; those extra harness tests and
configuration are not silently included in this PR. Its exact staged snapshot is tested independently.

The earlier native-client implementation passed `./scripts/verify.sh all` with `source_changed_during_run=false`. This is historical evidence, not acceptance of the later readability follow-up:

- `target/verification/20261118T011423Z-b654de3f/report.json`
- backend: 167 tests, 0 failures/errors/skips
- architecture: 13 tests passed
- readability/style/checkstyle/style-ratchet: passed
- browser journey: 1 passed, no missing artifacts

The readability follow-up passed 56 targeted configuration/protocol/native-client/diagnostic checks, including 7 new bean/route cases and 12 new label/circuit cases. Final acceptance uses the source-bound all-mode report cited in the handoff, plus the fresh `target/packaged-startup/report.json` with its tested JAR SHA-256. Do not update this document after a verification run and call the earlier source digest current.

The packaged check exercises explicit database reset/override, packaged CSS, login, stateless rates, create, duplicate replay, read-only lookup/status and PDF label against a loopback provider; it asserts one provider create across replay/recovery.

Slice-level evidence: `OutboundClientTest` covers shared pool/auth, same-origin/traversal rejection, header vs body timeouts with one dispatch and no retry, strict generic DTO decoding and one-pass path encoding; `SandboxProviderProtocolTest` covers malformed 2xx classification, status mappings, slice independence and a second provider's native interface; `OutboundDiagnosticsTest` covers redacted error logging and body-read status retention.

`SandboxHttpConfigurationTest` additionally verifies that Spring creates and injects working proxies with absent, blank, default and custom routes, and rejects unsafe configuration. New protocol checks prove malformed label JSON is counted/logged as a guarded failure with released permits, and repeated expected 400 responses do not open the availability circuit.

Pre-change baseline (not acceptance of the refactor): `target/verification/20261117T194341Z-ca2149fd/backend`.

## Preserved invariants

- No public API, schema, auth, booking/idempotency, recovery or frontend behavior change.
- Same-origin requests and traversal rejection occur before credentials are sent.
- Admission refusal before dispatch still reaches booking as known-unsent; unknown dispatched outcomes never become automatic retries.
- Diagnostics do not leak credentials or request bodies; expected statuses do not hide body-read failures.

## Remaining limitations and risks

- Write-path verification uses the local simulated carrier. The published development instance additionally passed login and read-only quotation through the configured provider; no real carrier create/retry or production performance measurement was performed.
- Provider quote ID uniqueness/reuse and deployment remain the earlier I10 release gates.
- Item H08 was verified by a native-interface fixture and the second-provider test; production currently registers only the sandbox provider.
- This branch is based on I10's committed state while PR #15 is open; merge ordering must keep that base to avoid presenting unrelated commits.
- Local development deployment and commit/push/PR were subsequently authorized by the user; completion evidence belongs in the final handoff. No merge or real carrier write is authorized by that local deployment request.

## Local publishing support

The existing launcher accepts `--run-jar <existing-file>` so the verified application can run from an
immutable release copy rather than `target/` files replaced by later builds. Default startup/reset behavior
is unchanged. Packaged acceptance now restarts that copy through the actual launcher and reads the same
confirmed booking without another provider create. Deployment uses existing local configuration and database;
it does not delete business data. Read-only checks against the configured provider are distinct from the
isolated simulated-provider write-path E2E.

Local development publishing completed on ports 8080/9091 from an immutable copy; the existing database
was backed up, not reset. Evidence is retained outside Git in `target/local-deployment/20261119T032214Z/`:
the deployment report records PID/artifact SHA-256, health UP, successful CSS/JS and authenticated lookup,
and unchanged booking count. `browser-read-report.json` records a fresh browser login followed by the
page's bundled shipping defaults, 18 displayed quotes and the review screen, stopping before creation.
The live-account browser check does not record traces/screenshots and blocks booking write requests.
An earlier hand-constructed read probe was rejected for missing CompanyName; that failure was retained
and is not presented as a failure of the default-input UI. Actuator's vendor JSON content type is supported
by the deployment probe. Full creation/recovery/label E2E remains isolated with synthetic accounts.

The published development artifact includes pre-existing local configuration/UI fixes in the working tree.
The I11 PR deliberately excludes those unrelated changes and the separate I9 harness work; its staged
snapshot is verified independently. The deployed artifact SHA-256, not an unchanged HEAD identifier,
identifies the running local build.

## PR #16 review follow-up — deadline boundary

The review identified an over-broad response-validation catch: an exhausted deadline before subscription
was logged as PROTOCOL_ERROR with status 0, and creation additionally changed PROVIDER_TIMEOUT to
SHIPMENT_OUTCOME_UNKNOWN. Rates, creation and labels now keep waiting/transport failures outside the
ShippingException response-validation catches. Native decoding failures and received invalid bodies/receipts
still receive protocol diagnostics; invalid creation receipts remain unknown outcomes. No retry permission
is added: the booking layer still handles uncertain provider failures conservatively.

SandboxDeadlineBoundaryTest deterministically exhausts the budget after actual guard admission without
sleeping. For all three APIs it asserts zero reactive subscriptions, preserved PROVIDER_TIMEOUT/category,
no false protocol/status-0 log, a timeout metric and released permits. All three cases failed before the fix;
the retained reproduction report is `target/verification/20261119T173110Z-167e3a41/report.json`.
