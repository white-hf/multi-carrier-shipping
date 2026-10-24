# Iteration 08 — Module boundaries and shared outbound infrastructure

Date: 2026-10-23
Status: Implementation and verification complete; awaiting user acceptance.
Plan: [I8](../iterations/iteration-08-module-boundaries.md).

## Delivered

- Replaced the multi-interface integration adapter with a provider client, payload mapper and provider-specific values; quote, booking, label and recovery each have a consumer-owned bridge.
- Centralized operation resource construction, bulkhead/breaker execution and deadline enforcement in shared HTTP infrastructure. Kept rate/create/label resources isolated and preserved disabled creation retries. Fan-out reuses guard execution without moving its orchestration.
- Retained shared monitoring AOP at the existing transport boundary, avoiding duplicate bridge measurements.
- Moved quote acceptance eligibility into ShippingQuote; kept existing request and booking domain behavior. Moved use-case commands and booking result projection out of domain.
- Added source dependency checks and focused domain/guard/deadline tests. Updated test composition to query recovery capabilities through its consumer port.

## Verification

- Clean backend regression: 153 tests passed, with zero failures, errors or skips. Includes dependency boundary, domain eligibility, shared guard ordering, no-retry and cancellation checks.
- Existing Playwright browser acceptance: one test passed against local HTTP stubs. No real chargeable creation was performed.
- No frontend files, API paths, migration scripts, production data or credentials were changed.

## Limits and rollout

The service remains a modular monolith; extraction still needs deployment/data ownership work. Read retries are not newly enabled. Shared DTOs, generic base repositories and empty package layers were not introduced. Existing ports retain their consumer-owned values. Database compatibility remains tested on SQLite only.

Review the changed source before restarting a running process; the running server is not automatically replaced. No commit or push is part of this iteration execution.

## Small structural follow-up: authentication

Moved controller, token service, authentication filter and identity-resolution configuration into the top-level `auth` module. Split shared correlation/MDC/response headers into an earlier tracing filter. Moved provider composition from shared configuration to `runtime`. Added a shared dependency boundary rule and three filter-chain tests covering authentication rejection, cross-origin rejection and verified identity/correlation propagation. No new iteration or frontend changes.

Follow-up validation: 157 backend tests passed in a clean build; the existing browser acceptance test passed. No frontend files changed.
