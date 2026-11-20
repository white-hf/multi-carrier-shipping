# I11 — Replace custom declarative HTTP with Spring HTTP services

**Date:** 2026-11-17. **Status:** native-client replacement and authorized readability follow-up implemented; final acceptance is the source-bound report cited in the handoff. See the [I11 summary](../summaries/iteration-11-spring-http-services.md) for test scope and limitations. **Branch:** `codex/spring-http-service-clients`.

## Background and goal

User requirement: use existing Spring Boot/Framework components in place of the project-owned declarative third-party API mechanism, without changing existing functional or technical requirements. This is a new engineering iteration following I10, not a new shipping feature.

Workflow follows the locally read `codex-agile-product-delivery` skill: engineering requirement → [design](../design/spring-http-services.md) → bounded iteration → validated implementation → execution summary. The skill was read from its installed SKILL.md; it is not advertised in this session's skill-tool catalog. Existing project tools/structure are reused rather than reinitialized.

## Scope and non-goals

- Replace endpoint/response dispatch DSL with native Spring HTTP interfaces and codecs.
- Preserve provider API slice independence, shared transport, protection, errors and diagnostics.
- Remove obsolete custom framework code/tests, replacing them with behavior-equivalent tests through native clients.
- Do not change public API/schema/auth/booking semantics, add automatic retries, introduce a new generic framework, upgrade Boot, alter frontend behavior or make real carrier writes.

## Vertical slices

1. **Rates and common transport:** native typed interface, strict codec/response status preservation, bounded pool/URI/diagnostic filters; compare real local HTTP requests/results/errors with baseline fixtures.
2. **Create and labels:** configurable native routes, receipt and pending/rejection/unknown mapping, PDF/URL decoding and known-unsent distinction.
3. **Remove old DSL and verify integration:** migrate generic/provider tests without weakening acceptance, delete unused old endpoint/status/decoder/client interfaces, update active design and code-navigation documentation.

## Acceptance and evidence

| ID | Required evidence |
| --- | --- |
| H01 | Native Spring annotated interfaces/factory are used for rates, create and labels; no remaining custom OutboundEndpoint/StatusPolicy/ResponseDecoder invocation |
| H02 | Existing request bodies, Basic auth, default/configured paths, one-pass variable encoding and origin/traversal protection remain correct |
| H03 | Rates 200/404/400/401/429/5xx/unexpected statuses and invalid/empty/malformed payloads preserve results/errors and circuit classification |
| H04 | Create 200/201 receipts, 400 rejection, other/invalid/timeout/lost outcomes preserve uncertainty and at-most-one dispatch; admission refusal stays RETRYABLE |
| H05 | Labels preserve 202/404/empty pending, PDF bytes, JSON allowlist, configurable paths and error behavior |
| H06 | Bounds, group/total permits, cancellation, circuit state, body limits and zero automatic retries remain; no permit leaks |
| H07 | Correlated HTTP/protocol/body-read logs retain safe status/reason and redaction; provider credentials/request bodies are not logged |
| H08 | Another independently declared provider service and a generic response type work without central sandbox/shared type imports |
| H09 | Full application/backend/browser/packaged startup, CSS and booking recovery paths remain compatible; architecture/style gates pass |

Baseline backend evidence: `target/verification/20261117T194341Z-ca2149fd/report.json`, before transport changes. This is baseline evidence only, not acceptance of the refactor.

Run targeted native-client/provider/diagnostic tests after each slice, then repository `./scripts/verify.sh all` and `python3 tests/system/packaged-startup.py`. Inspect current-source reports, failures/skips and retained artifacts. Existing harness work is still a separate uncommitted workstream; if a commit is requested later, validate its isolated staged contents independently. Record actual outcomes and remaining limitations in the I11 summary, not as prospective passing results here.

## Follow-up execution plan

- **Wiring:** move static proxy factories to slice-local Spring configuration; inject proxy beans into operations. Test that the actual configuration creates working proxies and uses the shared transport.
- **Routes:** replace SandboxRoutes with Spring property resolution over the normalized bound property. Test default, blank and custom paths and reject invalid configured paths.
- **Interpretation:** simplify label JSON parsing and name operation response/status mapping methods, preserving the guard boundary. Extend malformed-label and status/circuit regression checks.
- **Delivery:** retain earlier evidence across clean builds; run targeted checks, full repository verification and packaged-JAR feature checks; summarize results and limitations. Do not commit, push, deploy or call real carrier writes without a further task request.

Additional acceptance: interfaces are pure declarations; bean wiring has no circular dependency; native placeholder suffix/default resolution works; label JSON null/trailing data/wrong scalar types/disallowed URLs remain failures; successful/pending/definite-rejection/unknown outcomes and circuit/permit accounting stay unchanged. No universal execution helper is introduced.

## Authorized local delivery

The user subsequently requested deployment on this development machine, E2E acceptance, commit/push and a PR.
Use the existing configured database without reset. Verify a copied immutable JAR, stop only the identified
application process, launch the copy with the same configuration, and check readiness/assets/authenticated
read-only paths. The launcher supports `--run-jar <existing-file>` to avoid rebuilding a running artifact.
Packaged acceptance must prove this option starts the saved booking after restart without another provider
create. Local publishing does not authorize real carrier creation: write-path E2E remains isolated with
temporary data and a loopback provider. Keep deployment logs, PID and artifact SHA-256 outside Git.
