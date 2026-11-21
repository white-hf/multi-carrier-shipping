# I12 implementation summary — compact shipping workflow

## What changed

- Quote, booking and label behavior are one `shipping` feature with
  `api/application/contract/model/port/infrastructure/persistence/config`.
- Application services depend on consumer-owned `RateProvider`, `ShipmentProvider` and
  `LabelProvider` ports; sandbox adapters are the only classes that see both port and provider
  types.
- Provider protocol moved to a top-level `provider/sandbox` module with independent `rate`,
  `shipment` and `label` API slices. Each slice owns its HTTP interface, operation, DTOs and proxy
  configuration; adding an API does not modify existing slices or shared code.
- `shared/infrastructure/transport` supplies only mechanisms: WebClient/pool construction, strict
  JSON, same-origin checks, response bounds, native Resilience4j bulkhead/circuit, deadline and
  diagnostics.
- Reconciliation is separate again: `RecoveryJob`, `ReviewTaskRepository` and
  `ReconciliationConfiguration` depend on shipping only through `ShipmentRecovery`,
  `Candidate` and `ManualReviewReason`.
- Label reads remain inside `BookingService`; the HTTP controller remains one file.

Retired: quote/booking/reconciliation business-module duplication, port/adapter forwarding inside
the old three modules, direct-carrier fan-out, outbound templates, dependency registration and
logical groups.

## Preserved behavior

- API paths, status codes, response fields, auth, quotas and SQLite schema/snapshot fields.
- Claim-before-call, one insert winner, replay without resend, explicit RETRYABLE retry,
  version/status CAS, late confirmation, task-before-manual-review ordering.
- Conservative unknown outcomes, no automatic HTTP retry, strict provider JSON, label URL
  allowlist, bounded/redacted diagnostics, metric names.
- Provider admission rejection maps to `NOT_DISPATCHED` only for create; pre-subscription deadline
  expiry releases permits and remains `PROVIDER_TIMEOUT`.

## Evidence

- `./scripts/verify.sh all`:
  `target/verification/20261121T030038Z-5367eb63/report.json` — style, readability, architecture,
  backend (170 tests) and browser journey passed.
- `python3 tests/system/packaged-startup.py` — packaged JAR, database reset/override, prebuilt
  restart, stylesheet responses, login, rates, create, duplicate replay, read-only lookup and PDF
  label passed. Artifact SHA-256:
  `e71706a84294d0a3bfb6f5c4fa62a2f16e01f87f735b5e7e3b7f4d6318f05697`.

## Real sandbox evidence

- Real sandbox rates: 13 offers; one create: `201 CONFIRMED` with shipment ID and tracking;
  replay/status/lookup: same booking; label: `200 application/pdf`, 79,532 bytes; DB delta: one
  booking, zero tasks.
- An initial rates request without company names returned provider `400` and made no write; the
  accepted run used synthetic company names. No uncertain write was retried.

## Remaining limitations

- Automated checks used a loopback carrier simulation; one confirmed write was made against the
  configured real sandbox. Production carrier access remains unexercised.
- The browser journey always runs the full shared suite; selecting one feature does not imply an
  isolated run.
- No commit, push, merge or deployment was performed.
