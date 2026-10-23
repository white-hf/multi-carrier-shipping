# Shipping Web Application — Delivery Plan

**Version:** 1.3  
**Status:** I1, I2 and I3 accepted for the agreed demo scope after independent review follow-up (2026-10-23)  
**Design baseline:** [Backend technical design](../design/backend-design.md), including fixed demo user, user-based routing, core engineering challenges and the minimal HTML + Bootstrap 5 + Vanilla JavaScript frontend. External API names and the documentation reference are recorded in the design; detailed contracts stay in the supplied integration reference. Historical sandbox checks and current deterministic acceptance evidence are recorded separately in the acceptance report.

**Inputs:** [PRD v1.3](../prd/shipping-workflow.md), [requirements review](../prd/requirements-review.md), [product prototype](../prototypes/shipping-workflow.html).

## 1. Planning assumptions

Describe the target product and its later backend design as a new, self-contained system. Explain responsibilities, interfaces, data, consistency and failures without assuming the reader knows the existing code. Do not frame the design as a patch or migration from the earlier demo.

Execution may reuse, adapt or replace existing code after inspection. The user's estimate that about 80% of backend logic may exist is an unverified reuse hypothesis, not measured coverage, completed scope or a delivery commitment. Existing tests demonstrate the earlier baseline, not acceptance of this product. Every reused capability must satisfy the new requirements and tests.

The frontend is a minimal vehicle for the complete journey, implemented by a separate frontend engineer using HTML + Bootstrap 5 + Vanilla JavaScript. Backend delivery provides the unversioned `/api` contracts and concrete JSON/error examples before UI integration. No separate frontend technical design document is required. The agreed backend design is the implementation baseline; module-by-module development scope and frontend handoff are recorded there. Do not choose frameworks, API paths, schemas or detailed contracts through this plan.

Private integration access details, locations, script contents, credentials and operational contracts must not be copied into tracked documents. Integration verification uses private references; public planning describes application behavior and acceptance outcomes only.

## 2. Delivery contract

P0: shipment details → external rates → exact quotation selection → review → safe creation → confirmation → immediate or later label retrieval. Include validation, empty/error states, durable duplicate prevention, uncertain outcomes and simple local backend access configuration. No application login is required.

Support one Canadian domestic package across all provinces and territories, defaulting to IN/LB. Changed details invalidate selection; expired quotes require refreshed rates; a changed price requires explicit confirmation. Creation may remain confirmed while its label is pending. Never create another shipment to obtain a label.

P1: optional charge breakdown and presentation polish. Navigation preserves required shipment context; the integrated browser acceptance journey verifies input retention.

Exclusions remain those in the PRD. Independently deployed services, queues, distributed caches and production sharding are not delivery prerequisites. No fixed one-week commitment is made; estimate after design, integration dependencies and actual reuse are assessed.

## 3. Three vertical iterations

| Iteration | User-visible outcome | Exit gate |
| --- | --- | --- |
| I1 — Enter details and compare rates | User enters a valid shipment, obtains actual quotations and selects an exact option | Minimal UI/backend path works; validation, loading, empty/error states and selection invalidation demonstrated |
| I2 — Create safely and obtain a label | User explicitly confirms a shipment and receives its confirmed result and label, immediately or later | Durable write safety exists before external creation is enabled; duplicate, pending, failure, restart and delayed-label cases verified |
| I3 — Verify and prepare delivery | A reviewer can configure and reproduce the complete product journey | Cross-iteration acceptance, operational instructions, known limitations and verification evidence are complete |

The design baseline is agreed. Validate the relevant external integration behavior before enabling each real capability. They are preparation tasks, not an additional full iteration. Validation of the documented label lookup API is a dependency for completing the label portion of I2; it must not block unrelated I1 work.

## 4. Task checklist

Retain task identifiers for traceability. Tasks are checklist items within an iteration, not separate delivery iterations. Refine execution checklists against the agreed backend design; no design item is evidence of implemented behavior.

| Iteration | Task | Outcome / verification |
| --- | --- | --- |
| I1 | ST-001 — Runnable application and local setup | Configured startup, clear missing-setting errors, no secrets in Git or browser; reproduce from documented non-secret setup |
| I1 | ST-002 — Integration verification | Verify request/response behavior privately; record only non-sensitive application acceptance decisions in public documents |
| I1 | ST-003 — Application contracts | Deliver JSON/error examples for `/api/quotes`, booking, status and label contracts; backend-resolved demo user; immutable quote selection |
| I1 | ST-101 — Shipment details | Required address/contact fields, positive measurements, Canadian coverage and IN/LB defaults; field-level validation |
| I1 | ST-102 — Rate retrieval | Separate `SingleProviderRateEngine` behind `RateComparisonPort`; sandbox adapter results durably associated with original shipment details; bounded waiting and classified failures; no fabricated live results |
| I1 | ST-103 — Compare and select | Display returned service/currency/total/transit appropriately; preserve exact selection, invalidate after edits, refresh expired quotes and confirm changed prices |
| I2 | ST-301 — Durable attempt state | Persist operation identity and business state; identical retries resolve to one attempt, changed intent conflicts, state survives restart |
| I2 | ST-302 — Safe submission coordination | Atomic claim before any external creation call; no network call inside a long transaction; concurrent requests cannot submit the same operation twice |
| I2 | ST-201 — Shipment creation | Persist booking-owned submission facts and use the exact selected provider quote without a post-claim quote dependency; distinguish actual success, definite rejection and unknown outcome |
| I2 | ST-202 — Review and result | Explicit confirmation, disabled in-progress action, actual confirmation data and distinct failure/pending states |
| I2 | ST-303 — Operation status UX | Keep one operation identity across retries/status refresh; no automatic re-creation after an uncertain response or restart |
| I2 | ST-203 — Later label retrieval | Retrieve the label for the existing confirmed shipment; pending/unavailable label never changes creation to failed or triggers another creation |
| I3 | ST-402 — Complete journey review | Cross-check PRD acceptance, minimal responsive UI, keyboard use and all user-facing exceptional states |
| I3 | ST-403 — Handoff | Short README/run instructions, local configuration guidance without secrets, reproducible demonstration and explicit limitations |

**ST-401 — Automated verification runs after implementation and frontend integration are complete, as explicitly agreed with the user.** Prepare relevant checks while coding if needed, but do not execute automated suites during I1/I2 implementation. The final verification phase covers input/quote association, provider failures, concurrent duplicates, changed intent, uncertain responses, restart, delayed labels and the full browser journey. No automated test makes an external shipment-creation call. Implementation progress before this phase is not a claim of verified acceptance.

## 5. Dependencies and safeguards

- Complete the relevant design and integration checks before implementing each capability; avoid creating a large technical-only foundation phase.
- UI and backend can progress against the same agreed application contract, then demonstrate a complete path at the iteration gate. The frontend engineer owns UI implementation; backend delivery owns application contracts, persistence and provider integration. Both validate the end-to-end slice; no parallel agents are assumed.
- Durable attempt state and submission coordination are prerequisites to enabling the real creation path, not optional enhancements after it.
- Quote-context durability, request isolation, expiry signals, returned monetary semantics, shipping-date rules and supported lookup behavior are defined in the design baseline, with provider-specific behavior still requiring verification.
- Do not promise automatic reconciliation unless a safe provider lookup contract is verified. Pending/manual handling is preferable to unsafe creation retry.
- Creation confirmation and label availability are independent product outcomes; verify both immediate and delayed-label behavior.

## 6. Completion, triage and reporting

A slice is accepted only when its scoped behavior works, the deferred verification passes, the user journey has been demonstrated and a summary records evidence plus remaining gaps. Existing implementation or a passing legacy test suite alone does not complete a new requirement.

If time is constrained, defer optional breakdown and polish. Do not cut duplicate protection, unknown-outcome handling, correct quote association, core error states or relevant tests. If delayed label access is blocked by unverified integration behavior, report it explicitly; do not call the full P0 journey complete.

Future detailed plans use `iteration-01-<outcome>.md` naming and reference PRD IDs. Summaries use matching identifiers. No implementation tickets are marked complete by this roadmap, and no private integration details belong in summaries or evidence.

## Acceptance follow-up — 2026-10-23

The user approved closing four review findings within the existing iteration scope:

1. Validate provider shipment identity before confirming a booking; malformed success remains uncertain.
2. Verify lost responses, real HTTP timeouts, database reopening and failed confirmation persistence on the new workflow.
3. Pin browser dependencies and separate browser execution from ordinary backend tests.
4. Rerun acceptance and align delivery status with reproducible evidence.

All four findings are closed by 123 passing backend tests and a separately passing browser journey. No new product features, provider creation retries, automated compensation or background label polling are introduced. See [acceptance report](../summaries/acceptance-test-report.md) for the final decision, commands, test counts and limitations.
