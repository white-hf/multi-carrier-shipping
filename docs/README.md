# Product delivery documents

Current stage: **I1/I2/I3 accepted for the agreed demo scope after independent review follow-up**. The PRD records confirmed decisions and external sandbox verification items; the prototype is an interaction reference. The three-iteration roadmap is agreed in principle. Backend design describes the target as a new system, independently of existing implementation details.

## Directory structure

```text
docs/
├── README.md
├── prd/
│   ├── shipping-workflow.md
│   └── requirements-review.md
├── prototypes/
│   ├── README.md
│   └── shipping-workflow.html
├── design/
│   └── backend-design.md
├── iterations/
│   └── delivery-plan.md
├── summaries/       Implementation evidence and iteration retrospectives
└── runbooks/        Setup, demonstration and operational instructions
```

The iteration directory contains the reviewed roadmap. The backend design is the agreed implementation baseline; summaries contain implementation and acceptance evidence, and runbooks contain reproducible setup and test instructions. A directory or roadmap does not imply an implemented capability.

## Read in this order

1. [Shipping workflow PRD](prd/shipping-workflow.md) — users, scope, functional requirements, business rules and acceptance journey.
2. [Prototype guide](prototypes/README.md) and [interactive HTML](prototypes/shipping-workflow.html) — visual flow, with mock-only behavior clearly identified.
3. [Requirements review](prd/requirements-review.md) — understanding, execution-only code observations, inconsistencies and open decisions.
4. [Backend technical design](design/backend-design.md) — target architecture, module contracts, data model, application APIs and failure handling; agreed baseline; actual integration behavior remains subject to verification.
5. [Delivery plan](iterations/delivery-plan.md) — three end-to-end slices; detailed scope follows backend-design discussion.

## Agile document ownership

| Stage | Location | When to create/update |
| --- | --- | --- |
| Product requirements | `prd/` | Product behavior, priorities and acceptance outcomes change |
| Product prototype | `prototypes/` | Clarify or validate the user journey |
| Backend technical design | `design/` | After requirements discussion; contracts, boundaries, data and failure handling |
| Iteration plan | `iterations/` | Review proposed slices first; approve scope after relevant product/design decisions |
| Execution summary | `summaries/` | After a slice is implemented and validated; evidence and remaining gaps |
| Operating guidance | `runbooks/` | When new behavior needs deployment or operation instructions |

Create later-stage documents only when there is content to own. The roadmap defines intended outcomes, not completed iterations, a fixed delivery estimate or a finalized technical design. Existing tests remain in the Maven `src/test/java` structure; this documentation organization does not change the application.

Each future iteration should identify requirement IDs, a complete user-visible outcome, implementation scope, acceptance checks, evidence and residual risks. Do not mark work complete from a plan or prototype alone.

## Naming and lifecycle

Use lowercase English kebab-case for document and prototype filenames: `shipping-workflow.md`, `requirements-review.md`, `delivery-plan.md`, `shipping-workflow.html`. Keep `README.md` as the conventional directory index. Use lowercase directory names. Keep version, review status and dates inside the document rather than adding `_v2`, `FINAL` or dates to filenames.

- Delivery roadmap: `iterations/delivery-plan.md`; this summarizes the overall iteration sequence.

- Backend design: `design/backend-design.md`. No separate frontend technical design document.
- Iteration plans: `iterations/iteration-01-<outcome>.md`; continue numbering only after the proposed split is reviewed.
- Delivery summaries: `summaries/iteration-01-<outcome>.md`, using the same identifier as the plan.
- Runbooks: descriptive names such as `runbooks/local-setup.md`; create them when there is implemented behavior to operate.

Plans should state review/approval status, product requirement IDs, outcome, scope, dependencies, deliverables and acceptance evidence. Review iterations as complete user-visible slices rather than one iteration per class, API method or technical layer. Summaries record actual changes, verification, remaining issues and lessons; a plan is not evidence of completion.

Existing executable demo instructions remain in root `demo.md` until a product iteration changes them. Private integration access details and secret values must not enter any documentation directory.

## Working agreements for this iteration

- Treat the PRD's working product as externally integrated; prototype fixtures and the existing demo adapters are not acceptance evidence for actual rates or shipment creation.
- Prefer the simpler API-calling approach required by the new integration. Reuse existing safeguards where relevant; decide their scope in the backend design discussion.
- Keep the frontend minimal and focused on the full journey. Do not create a separate frontend technical design document.
- Write the target backend design as a new, self-contained system rather than an explanation of changes to the earlier demo.
- During execution, separately inspect existing code for reuse/adaptation. The estimated 80% backend reuse is a hypothesis, not verified requirement coverage. Keep requirements, chosen design and actual implementation claims distinct.

The supplied PRD/prototype headings and display branding were normalized to the independent-demo wording already used by the repository. Requirement IDs and prototype JavaScript behavior were preserved. PRD v1.3 incorporates the confirmed product decisions, including F12/F13 promoted to P0, fixed backend-configured demo-user ownership and HTML + Bootstrap 5 + Vanilla JavaScript. The backend design covers core engineering challenges and user-based shard routing; external API names and the public documentation reference are recorded without copying parameter schemas; historical sandbox verification and current acceptance evidence are recorded in the acceptance report. The PRD's document-boundary section reflects the requested backend-design discussion and minimal frontend scope.

[Current demo overview](../README.md) · [Existing demo instructions](../demo.md)

## Current implementation handoff

- [Application API](design/application-api.md) — concrete frontend request/response and error examples.
- [Manual startup and acceptance](runbooks/manual-acceptance.md) — startup scripts and the browser acceptance journey.
- [Workflow setup](runbooks/workflow-setup.md) — local configuration, profiles and startup; no credentials are included.
- [Frontend integration](summaries/frontend-integration.md) — supplied assets and integration corrections.
- [I1 implementation record](summaries/iteration-01-compare-rates.md) and [I2 implementation record](summaries/iteration-02-create-shipments.md) — implemented scope and verification evidence.
- [I3 delivery checklist](iterations/iteration-03-delivery.md) and [I3 summary](summaries/iteration-03-delivery.md) — handoff and final verification evidence.
- [Acceptance & E2E test report](summaries/acceptance-test-report.md) — full English test report covering I1, I2, I3, Playwright browser E2E, and live ShipTime Sandbox verification.

Backend checks run with `./mvnw clean test`. Browser checks run separately with `npm ci`, `npm run browser:install`, and `npm run test:browser`. Both use controlled local HTTP provider stubs without creating chargeable external shipments; see the workflow runbook.

## Follow-up iteration

[I4 — Manual review for unresolved shipments](iterations/iteration-04-manual-review.md): accepted on 2026-10-23. See the [I4 summary](summaries/iteration-04-manual-review.md). I1–I3 acceptance remains the recorded baseline.

[I5 — Shipping label actions](iterations/iteration-05-label-actions.md): browser-based label actions; accepted on 2026-10-23; see the [I5 summary](summaries/iteration-05-label-actions.md).

[I6 — Idempotent requests and retryable rates](iterations/iteration-06-request-idempotency.md): user-scoped rate request idempotency, version-fenced retries, read-only quote status lookup and 24-hour browser draft recovery; accepted on 2026-10-23; see the [I6 summary](summaries/iteration-06-request-idempotency.md).
