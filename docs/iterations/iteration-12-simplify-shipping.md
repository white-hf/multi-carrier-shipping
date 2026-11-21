# I12 — A compact shipping workflow

Status: implemented and verified in the current worktree. Branch: `codex/simplify-shipping-workflow`.
No commit, push or deployment is part of this iteration.

## Authorized problem and scope

The user approved one shipping business unit instead of separate quote/booking/reconciliation
modules, retirement of the direct-carrier fan-out demo, and removal of generic outbound
templates/groups/dependency registration. Preserve the current aggregator product: rates, booking
claim/replay, explicit known-unsent retry, read-only recovery, manual review, labels, authentication
and existing data/API formats.

The approval does not retire dependency inversion, provider isolation or independent provider API
slices. See the [compact shipping design](../design/compact-shipping.md).

## Implementation slices

1. Merge controllers/services and concrete JDBC repositories under `shipping`; keep one controller
   and keep label reads inside `BookingService`.
2. Restore consumer-owned ports (`RateProvider`, `ShipmentProvider`, `LabelProvider`) and adapters
   under `shipping/infrastructure/provider`; application and provider never import each other.
3. Move provider protocol back to a top-level `provider/sandbox` module with independent
   `rate/`, `shipment/` and `label/` API slices. Shared transport/protection/diagnostics stay in
   `shared/infrastructure/transport` as mechanisms only.
4. Keep reconciliation separate with its own scheduling, task table and
   `shipping.contract.ShipmentRecovery` dependency.
5. Migrate behavior tests and architectural checks instead of weakening assertions.
6. Validate API, real SQLite concurrency/restart, admission/deadline/body/circuit edges, full
   isolated browser flow and packaged startup.

## Acceptance

- Public URLs/status codes/JSON, auth/isolation/quotas, schema and snapshot field names remain
  unchanged.
- Quote retrieval has no business writes; duplicate booking identity has one insert winner/initial
  sender.
- Only explicit RETRYABLE retry may send; uncertainty/manual review/reads never resend.
- Task registration precedes manual transition and is idempotent across interruption and late
  confirmation.
- Fixed shared capacity, no-wait rejection, circuit semantics, remaining deadlines and zero
  automatic HTTP retries remain.
- Strict response validation, allowlisted labels, same-origin checks, bounded/redacted diagnostics
  and stable metrics remain; monitoring failure cannot change business results.
- Provider additions are additive: a new provider API adds its own slice and proxy configuration;
  a new vendor adds a provider module plus adapters without changing shipping services.
- Fan-out and template/group extensibility are explicitly retired; they are not regression
  requirements.

## Execution summary

Implemented in the current worktree:

- `shipping`: `api`, `application`, `contract`, `model`, `port`, `infrastructure/provider`,
  `persistence`, `config`.
- `provider/sandbox`: `rate`, `shipment`, `label` slices with provider-owned result types.
- `reconciliation`: `RecoveryJob`, `ReviewTaskRepository`, `ReconciliationConfiguration`.
- `shared/infrastructure/transport`: pool/WebClient/strict JSON, native Resilience4j protection,
  bounded diagnostics.
- Label reads stay in `BookingService`; the controller remains one file.

## Real sandbox E2E (project environment)

Executed against the configured real sandbox host with the project launcher, `.env`, ports and
project SQLite database:

- rates returned 13 usable CAD offers;
- one create returned `201 CONFIRMED` with a provider shipment ID and tracking number;
- same-identity replay returned `200` for the same booking; status and provider-identity lookup
  returned the same booking;
- label returned `200 application/pdf` (79,532 bytes) without another create;
- database delta was one booking and zero manual-review tasks.

The first real rates attempt returned provider `400` because company names were empty; that attempt
made no write. Adding synthetic company names produced the accepted baseline. No retry was issued
after any uncertain write outcome.

Verification evidence (current source):

- `./scripts/verify.sh all` — `target/verification/20261121T030038Z-5367eb63/report.json`:
  style, readability, architecture, backend (170 tests) and browser journey passed.
- `python3 tests/system/packaged-startup.py` — packaged JAR, reset/override, restart, CSS, rates,
  create/replay, read-only lookup and PDF label passed; artifact SHA-256
  `e71706a84294d0a3bfb6f5c4fa62a2f16e01f87f735b5e7e3b7f4d6318f05697`.

The verification harness itself is maintained in the local worktree and is not included in this
PR. Automated checks used a simulated carrier; the real-sandbox run above made one confirmed
sandbox write. Production carrier access and deployment remain separate actions.
