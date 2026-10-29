# Real sandbox validation plan

Date: 2026-10-29. Status: **Cases defined; live execution pending provider-refactor integration.**

Final integration acceptance means browser → application → real sandbox → persisted result → browser. The existing Maven/browser suite uses local provider stubs and remains useful regression evidence, not fresh external acceptance. This plan does not claim a live runner has been implemented.

## Code-derived validation boundaries

Baseline reviewed: main commit `095dfc4`. Recheck these boundaries after the provider refactor; private class names are not test dependencies.

| Input/rule | Browser | Backend | Reaches real provider? |
| --- | --- | --- | --- |
| Required contact, street, city, phone, postal fields empty/whitespace | Rejects | Also validates required fields | No |
| Nonempty invalid email | HTML email validity | Email validation | No |
| Zero/negative measurements, >10000, >4 effective decimals | Rejects | Bean Validation | No |
| Measurement 0.05 | HTML min=0.1 rejects | Backend allows positive values <=10000 with <=4 decimals | Only via direct API; record this boundary difference |
| Invalid postal syntax or letters in phone | No matching HTML pattern; may pass | Rejects | No |
| Unknown province/country/unit, malformed JSON, missing nested objects | UI restricts or cannot express some cases | Rejects | No |
| Postal/city/province inconsistent but individually well-formed | Allows | No geographic cross-check | Yes |
| Plausible but nonexistent street | Allows | Checks length/nonempty, not deliverability | Yes |
| Carrier-specific package limits within local numeric bounds | Allows | No carrier-specific limit check | Yes |
| Past shipping date | UI omits date | Service rejects explicit past date | No |
| Same key with changed input | UI normally creates a new key | Fingerprint conflict | No new provider call |
| Missing/foreign/expired quote on booking | UI restricts selection | Ownership/eligibility checks | No create call |

Absent units default to IN/LB. Missing or blank packageType defaults to PACKAGE. Do not write tests claiming these normalized inputs are rejected. Decimal normalization strips trailing zeros, so use 1.12345 rather than 1.00000 as an overprecision negative case.

## Case expectations and fixtures

The [JSON case catalog](../../tests/sandbox/quote-validation-cases.json) defines independent input mutations and expected interception points. [Catalog instructions](../../tests/sandbox/README.md) explain browser/API entry modes. It is declarative test data, not a runner.

- **FE-01–06:** empty/whitespace required fields, invalid email, zero weight, browser minimum and precision violations. Assert no quote POST and no provider rates call. Guest-auth traffic is not a quote request.
- **BE-01–08:** invalid postal/phone/province/country/unit, missing weight, past date and unknown JSON field. Supply valid guest auth and fingerprint so the intended input validator, not authentication, rejects the request. Postal/phone cases should also be exercised through the browser because those formats currently pass its validation.
- **SB-01–05:** sender/recipient province mismatch, city mismatch within/between provinces, and postal mismatch. All must pass local validation unchanged and make one real rates call.
- **SB-06–07:** unverified street and locally allowed but unrealistic parcel size. These are provider-validation candidates, not guaranteed 400 fixtures.

Establish one positive baseline that actually returns provider 200 and usable rates before testing mutations. The sample addresses are public/synthetic examples derived from the UI, not proven deliverable fixtures. Stop if baseline auth/configuration fails; do not reinterpret an invalid baseline as successful negative coverage.

The actual address combination that previously produced 400 must be reproduced locally before it is promoted to a confirmed negative fixture. Its exact values are not established in this catalog. A candidate returning 200 or 404 is `FIXTURE_NOT_TRIGGERED`: it did not prove the intended validation rule, and must not be reported as a passing 400 test or automatically blamed on application code.

For a confirmed provider-400 case, assert the full path:

1. Browser accepts the individually valid fields and sends the quote request.
2. Backend accepts format/date validation and forwards the intended values through its normal adapter.
3. A real, correlated provider call returns 400; record sanitized messages for diagnosis.
4. Local API returns 400 / PROVIDER_REQUEST_REJECTED; the saved execution is RATE_FAILED.
5. UI stops loading, preserves editable input and offers recovery; it must not show NO_RATES, successful rates, an authentication screen or service-unavailable guidance.
6. Correct the address; UI uses a new key for changed content. A fresh real call retrieves rates and permits normal progression.
7. Repeated confirmed validation rejections do not open the availability circuit. Bound the live repetitions to the isolated test policy; use local deterministic tests for exhaustive circuit thresholds.

A generic PROVIDER_REQUEST_REJECTED message may still fail the user-guidance criterion if it gives no actionable direction. Record that as a UI gap rather than accepting backend status alone. Do not expose raw provider messages to the user as a shortcut.

## Stateful and creation cases

| ID | Scenario | Expected boundary and result |
| --- | --- | --- |
| FLOW-01 | Edit input after obtaining a quote | Invalidate the selection; changed intent gets a new key |
| FLOW-02 | Retry the same key with different payload via public API | Local 409; zero additional rates calls |
| FLOW-03 | Repeat a successful matching quote request | Reuse valid stored offers; zero additional rates calls |
| FLOW-04 | In-flight duplicate quote submission | No duplicate execution under current claim policy; UI handles response-only contention without replacing a final result |
| BOOK-01 | Blank/missing/oversized quoteId | Local validation; zero create calls |
| BOOK-02 | Unknown quoteId | Local 404; zero create calls |
| BOOK-03 | Quote owned by a different guest | Local ownership rejection; zero create calls |
| BOOK-04 | Expired quote or past request date | Local conflict; zero create calls; real expiry must be observed without falsifying the production clock |
| BOOK-05 | One valid creation and its same-key replay | One external creation total; replay retains booking/shipment identity |
| BOOK-06 | Same request with another booking key | Local conflict; no second external creation |
| BOOK-07 | Real provider create-400 | Requires a reproducible fixture accepted by rates but rejected at creation; no guessed-invalid tokens or bypass of quote checks |
| LABEL-01 | Label for the confirmed shipment | Retrieve/view actual PDF or documented label result without creating again |

BOOK-07 is pending until such a fixture exists. Local bad quote IDs do not prove external create-400 coverage. No automated retry after an uncertain creation; stop and preserve the identity for investigation. Do not manufacture duplicate-charge scenarios to test safety. Read-only quote cases never automatically proceed into booking.

## Execution gates and evidence

- Wait for the other agent's provider/request-wrapper PR to be reviewed and integrated. Record its exact commit and recheck the API contract before running. Do not bind tests to sandbox/aggregator class names or internal exception types.
- Use a separate application instance, temporary SQLite database/log directory, distinct loopback application/management ports, and a fresh browser context. Leave the demo database and existing configuration untouched.
- Load provider access settings from ignored local configuration without copying values or private integration-reference locations into docs. Demo mode must be off. Do not intercept provider responses or replace the downstream transport with a stub in the real E2E run.
- Use the existing slow-provider budgets (rates 90 seconds; browser HTTP budget 125 seconds). Run serially, one attempt per candidate; stop on auth/configuration failure or uncertain write outcome. Setup traffic and calls are counted separately from the assertion phase.
- Prove the boundary with browser quote POST counts, per-request backend correlation, isolated outbound metrics/call evidence and actual provider response metadata. A local HTTP 400 alone cannot identify which layer rejected it. Missing provider evidence means `INCONCLUSIVE`, not pass.
- Record case ID, commit, input mutation, local/provider status, local error code, correlation/request IDs, database state, UI behavior, duration and verdict. Store only sanitized summaries in Git; credentials, guest tokens, headers, raw private payloads and sensitive captures stay out of reports.
- Keep local stub tests for 401/429/5xx, timeout and concurrency faults that cannot safely or deterministically be induced externally. Never label them as real sandbox validation.

Verdicts: `PASS`, `FAIL`, `FIXTURE_NOT_TRIGGERED`, `BLOCKED`, `INCONCLUSIVE`, `NOT_RUN`. This plan has no recorded live passes. Existing acceptance counts describe their original local-stub scope only.
