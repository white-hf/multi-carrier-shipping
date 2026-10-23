# Shipping workflow — requirements review

Status: **for discussion**, based on PRD v1.3 and the supplied HTML prototype. This records product understanding and the existing implementation gap; it is not a technical design or implementation plan.

## Product outcome

A shipping customer or operator, without signing in, prepares one Canadian domestic package, retrieves rates, selects exactly one quotation, reviews the same shipment details and service, creates the shipment, and accesses the actual returned confirmation and label when available.

Default units are inches/pounds. Sender/recipient details include contact and phone, street, city, province, postal code and country; company, line 2 and email are optional. Residential classification is optional. Shipping date should be initialized to a valid date rather than entered manually.

The three UI steps contain four states: details, rate comparison, review before creation, and creation result. The final step must distinguish success, confirmed failure and pending confirmation. Returning to edit must preserve inputs; changed inputs must not silently retain a quotation for a different shipment.

## Scope and acceptance

P0 covers shipment details/validation, external rate retrieval, carrier/service/total/currency/transit presentation, exact quotation selection, explicit creation, real confirmation/error states, duplicate safety, uncertain outcomes and simple local integration setup (F01–F08, F12–F13; FR-01–FR-07).

The user confirmed F12/F13 as P0. Sorting, optional charge breakdown and the F11 input-preservation listing remain P1; FR-01 already requires retained inputs, so that remaining priority inconsistency still needs alignment.

Registration, payments, multiple packages, bulk shipping, history, full tracking, customs, cancellation and administration remain outside scope. Frontend work supports the journey; backend design and integration correctness are the technical focus. The working frontend choice is HTML + Bootstrap 5 + Vanilla JavaScript served by the backend.

## Confirmed product decisions

| Decision | Confirmed behavior |
| --- | --- |
| Duplicate and uncertain creation | P0; reuse operation identity on retry and show Pending Confirmation without automatic resubmission |
| Edited shipment / expired quote | Clear the selection and retrieve fresh rates; require explicit confirmation of any changed price |
| Created shipment without a label | Keep shipment creation confirmed; retrieve its label separately rather than recreating it; label lookup API identified in the supplied integration reference; behavior to validate |
| Application access | No login or registration; fixed backend-configured demo user; authenticated principal replaces this identity in a production deployment; integration access settings are backend-owned, local, excluded from Git and simple to provide for a demonstration |
| Domestic coverage | All Canadian provinces and territories; one package; IN/LB defaults |

External access credentials are not application-user credentials. No user system is being added to reuse them. Local setup behavior belongs to the product requirements; private values, endpoints, access scripts and locations are excluded from these documents.

## Existing-code inspection notes — execution only

| Area | Existing implementation | Product gap to resolve |
| --- | --- | --- |
| User journey | Backend APIs and a separate mock HTML prototype | Working UI must exercise rate lookup, selection, review, creation and status |
| Shipment input | Postal/country data and normalized kg/cm parcel measurements | Full sender/recipient contact/address, residential option, package type and shipping date |
| Units | Explicit KG/LB and CM/IN conversion | UI should initialize IN/LB and preserve entered values |
| Rates | Simulated carrier adapters; service, amount, currency and transit days | Verify actual API contract, input mapping and returned tax/charge semantics |
| Quote selection | Booking accepts carrier/service and parcel; no exact quote identity | Preserve association among original input, exact quotation and creation request |
| Booking | Unique owner/key claims, fingerprint, aggregate state machine | Adapt product input and actual provider creation/confirmation fields |
| Recovery | Independent task discovery/inquiry and durable observations | Verify provider idempotency, lookup and ambiguous-outcome behavior before choosing recovery scope |
| Ownership | Fixed owner in demo; authenticated principal outside it | No application login; use fixed demo-user ownership and user-scoped persistence/uniqueness without treating integration credentials as user identity |
| Labels | Simulated text label | Retrieve a label for an already-confirmed shipment; lookup API identified; behavior to validate |

The target technical design must stand alone as a new system; the table above is an execution/reuse aid, not a requirement to preserve the existing architecture. The estimated 80% backend reuse is unverified and does not mark any product requirement complete.

The new external calling approach may be simpler than the current per-carrier fan-out demo. Do not assume the new provider exposes individual carrier endpoints or requires the same orchestration. Existing ports, error handling, validation, metrics and write-safety patterns are reusable candidates; the proposed technical design is now recorded in [backend design](../design/backend-design.md), pending review.

## Prototype review

The HTML implements editable shipment fields, positive-measurement checks, selectable price cards, review, backward navigation and a simulated completion state. It preselects the first fixture quote. Rates and IDs are fixed; no API is called and the label action is disabled.

Gaps against the PRD include field-specific errors, loading/progress, empty/rate failures, creation failures/pending confirmation, email/residential controls and shipping-date behavior. Province choices are limited to Ontario and Nova Scotia. These are prototype limitations, not approved reductions in product scope. Treat the prototype as a flow reference rather than an implemented acceptance journey.

## Decisions before backend design

| Question | Why it matters |
| --- | --- |
| Is preserving inputs a baseline requirement? | F11 is P1, but FR-01 and the navigation acceptance already require retained inputs |
| Are returned totals always tax-inclusive, and how are missing breakdowns/transit estimates represented? | Do not invent tax, totals or delivery promises |
| Should a quote start selected? | Explicit user selection is recommended; the supplied prototype currently preselects the first result |
| What constitutes a valid default shipping date? | Provider timezone, cutoffs or non-shipping days may affect the accepted date |

The supplied integration reference identifies the label API; its runtime behavior and the remaining product preferences require verification. Validate integration semantics using private references without copying their access details or contents into project documents. No credentials are needed in these documents. The [backend design draft](../design/backend-design.md) now records module contracts, core engineering challenges, four-table persistence and user-based shard routing. Review it and the external API contract before refining vertical iterations with end-to-end acceptance. Do not create a separate frontend technical design or start implementation from this review.
