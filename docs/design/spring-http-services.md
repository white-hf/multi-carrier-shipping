# Spring HTTP Service Interfaces — I11 design

**Date:** 2026-11-17. **Status:** implemented; behavior-preserving replacement authorized by the user. Evidence: [I11 summary](../summaries/iteration-11-spring-http-services.md).

## Problem and selected solution

The current `OutboundEndpoint`, `StatusPolicy`, `ResponseDecoder` and `OutboundClient.invoke/register` form a custom declarative HTTP framework on WebClient. Request mapping, parameter expansion, serialization and generic response typing are already provided by Spring 6.1.

Use native `@HttpExchange` / `@PostExchange` / `@GetExchange` interfaces, `HttpServiceProxyFactory` and `WebClientAdapter`. Keep WebClient/Reactor Netty rather than move to a different blocking transport: the current pool, cancellation and deadline behavior already depend on it. No Boot upgrade, OpenFeign dependency or replacement client DSL is needed.

## Ownership and flow

```text
Existing module port / consumer adapter
    → Provider operation (contract interpretation + guarded execution)
    → Native Spring HTTP service interface
    → WebClient / Reactor Netty (shared dependency pool)
    → External provider
```

- Each provider API slice owns a pure native interface and a Spring configuration that creates its proxy bean with operation-specific WebClient status handlers. Adding an API must not require importing its types into `SandboxProviderConfig` or shared infrastructure.
- `SandboxProviderConfig` wires common transport, account context, the existing guard and a provider-scoped route resolver. Operation constructors inject the HTTP service interface; proxy construction no longer appears in operations or contracts.
- Spring handles method/body/path-variable binding, parameterized DTOs and configurable route placeholders. Existing `shipping.provider.shipments-path` remains the route source. Spring PropertySourcesPropertyResolver reads its normalized bound value so blank-to-default and startup validation are unchanged; no custom placeholder replacement remains.
- Delete the custom endpoint/status/decoder invocation DSL and its unused imperative Exchange API after consumers move. Do not wrap Spring in another declarative framework.
- Retain OutboundClientRegistry only as dependency policy/pool/lifecycle composition, plus same-origin validation and response diagnostics. Existing Resilience4j admission/circuits and absolute deadline enforcement remain in OutboundGuard/OutboundDeadline.

## Contract preservation

Native per-operation `defaultStatusHandler` rules reject statuses that should not decode, before Jackson sees an error body. Provider operations interpret WebClientResponseException statuses into their existing values/errors:

| API | Preserved behavior |
| --- | --- |
| Rates | 200 decodes/validates; 404 returns no offers; 400 is caller input rejection; 401/429/5xx/other statuses keep existing read error codes |
| Create | 200/201 require a validated receipt; 400 is definite rejection; every other dispatched status or invalid receipt is unknown, never known-unsent |
| Label | 202/404 are pending; remaining successful responses support empty, PDF or allowlisted JSON URL; read failures keep existing mapping |

Keep strict scalar coercion, integer bounds, trailing-token rejection and DTO validation using Spring's Jackson codecs with the existing ObjectMapper settings. Do not register a provider ObjectMapper as the application's global mapper. The old JSON decoder accepted JSON success bodies regardless of response Content-Type; retain that compatibility for typed provider DTOs while the label contract still checks PDF/JSON media types explicitly.

Decode and validate inside the guarded scope so invalid responses count as failures, not successes. Status exceptions must be caught there too: expected 404/400 business outcomes should retain existing circuit semantics. An outer admission rejection must still escape to the booking adapter as proven not dispatched.

## Transport, diagnostics and safety

- One shared connection pool per dependency; preserve optional group capacity and independent group circuits.
- Preserve existing total deadlines, connect/read/acquire timeouts, response limits, interruption/cancellation and permission release. Disable Netty automatic retries, including connection-close replay of POST.
- Validate resolved requests remain on the configured origin; forbid traversal/unsafe paths before sending credentials. Spring URI expansion must encode spaces, Unicode and percent values once.
- Keep bounded/redacted error-body logs, request correlation, HTTP status and body-read/cancellation classification. Reactor Context carries per-call diagnostic information without adding private metadata headers to provider requests.
- Response diagnostics may buffer/replay bounded bytes for native decoding, as before. This is instrumentation, not another protocol decoder. Provider-specific PDF/URL interpretation remains local to the label slice.
- No application endpoints, persisted data, booking state rules, auth semantics or UI flows change.

## Delivery and rollback

Use `codex/spring-http-service-clients`, based on the latest committed I10 code while PR #15 is still open; preserve unrelated local work. Implement/test one full provider slice first, then creation/labels and remove unused framework code. No historical business data or schema change is involved. Rollback is to the previous application artifact/configuration, without a database rollback. Do not deploy or call real carrier writes as part of this task.

Provider protocol, diagnostics, admission, API, browser and packaged-JAR evidence must pass before declaring compatibility. Real-provider acceptance and production performance are not inferred from local HTTP simulation.

## Readability follow-up — authorized scope

The next slice reduces wiring and decoding ceremony without introducing a generic proxy factory or `outbound.call()` callback framework:

1. HTTP service interfaces contain annotations and method signatures only. Each existing API slice owns a small Spring configuration that creates its proxy bean using native HttpServiceProxyFactory; operation constructors inject that interface.
2. A provider-scoped Spring PropertySourcesPropertyResolver resolves native annotation placeholders from the already-normalized bound shipments path. Delete SandboxRoutes and its fixed-string replacement. Preserve blank-to-default behavior and configured create/label routes; never register this resolver globally.
3. Label bytes are already buffered by the bounded transport, so decode JSON with the same provider ObjectMapper directly. Preserve empty-body pending, JSON null rejection, strict types/trailing tokens, URL allowlisting and safe error classification.
4. Extract provider-specific response interpretation into plainly named operation methods. Keep status mapping, DTO validation and conversion inside the existing guard; do not turn provider rejection into a circuit failure or classify admission rejection as a dispatched unknown result.

Existing diagnostic infrastructure is retained rather than mechanically split by line count. The objective is a visible Spring wiring boundary and readable operation flow, not a target number of files/lines. Public APIs, database state, resource limits, retries and frontend behavior are unchanged.
