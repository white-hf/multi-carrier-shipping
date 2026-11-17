# Multi-Carrier Shipping — Rate Comparison & Shipment Creation

**Product Requirements Document | Version 1.5 | Stateless Quotes; Implementation Verification in Progress**

**Context:** Independent shipping web application — product iteration  
**Product:** Web application

**Decision update (2026-11-16):** Quotes are transient; only explicit booking submission creates a durable business record. The [I10 optimization plan](../iterations/iteration-10-stateless-quotes.md) supersedes earlier quote-persistence and random-key requirements. This document describes the target, not current implementation acceptance.

## 1. Product Overview

Build a simple web application that enables a shipping operator to enter sender, recipient, and package details, compare real-time shipping services from multiple carriers, select a quote, and create a shipment with an accessible shipping label.

**Core journey:** Shipping Details → Compare Rates → Review & Confirm → Shipment Confirmation.

**Goals:** clear entry and validation; transparent comparison of cost and transit time; guided shipment creation; user-friendly handling of failures and uncertain outcomes. Preserve existing account login; the backend resolves the authenticated user for booking isolation.

## 2. Scope and Priorities

### Required (P0)

| ID | Feature | Description |
|---|---|---|
| F01 | Shipment details | Sender, recipient, one package |
| F02 | Validation | Required values and positive dimensions/weight |
| F03 | Get rates | Retrieve shipping quotations using entered details |
| F04 | Display rates | Carrier, service, total price and transit estimate |
| F05 | Select service | Select precisely one carrier/service quote |
| F06 | Create shipment | Explicitly confirm selected service |
| F07 | Confirmation and label access | Display returned ShipID; obtain the label separately when it is not immediately available |
| F08 | Error handling | Field errors, no rates, failures and progress indicators |
| F12 | Duplicate safety | One booking per user/provider/provider quote identity; existing submissions return the original booking without dispatch |
| F13 | Uncertain outcomes | Show Pending Confirmation; never resend creation automatically |

### Recommended (P1)

| ID | Enhancement |
|---|---|
| F09 | Price ascending sort (default) |
| F10 | Expanded breakdown: base, surcharges, tax |
| F11 | Preserve inputs when navigating backward |

### Out of Scope

Account registration, payments, multiple packages, bulk shipping, shipment history, full tracking, international customs workflows, address books, shipment cancellation, administrative dashboards.

## 3. Users and Key Flow

**Primary user:** shipping customer or operator preparing a Canadian domestic single-package shipment. All Canadian provinces and territories are supported; carrier/service availability remains dependent on returned rates.

1. **Shipping details:** enter origin, destination and package; validate; request rates.
2. **Compare rates:** display all returned options; select one; continue or edit information.
3. **Review and confirm:** verify the selected service and shipment summary; explicitly submit.
4. **Result:** present ShipID and label link on success; clear error or uncertain-status guidance otherwise.

## 4. Functional Requirements

### FR-01 Shipping Details (P0)

Sender and recipient each support: company name (optional), contact name, street address, address line 2 (optional), city, province, postal code, country (Canada), phone, and optional email. Address type may be represented by a residential toggle (default false).

One package supports length, width, height, and weight. Package type defaults to `PACKAGE`; units default to inches and pounds (`IMPERIAL`). Shipping date is initialized to a valid shipping date rather than requiring manual entry.

**Product validation:** contact name, street, city, province, postal code, country, and phone are required for the demo; numeric measures must be positive. Exact API-required fields remain subject to verification. Show inline errors and retain inputs after navigation.

**Acceptance:** a valid request progresses; invalid form blocks submission with field-specific errors.

### FR-02 Rate Retrieval (P0)

“Get Shipping Rates” validates input, calls the provider, normalizes its offers and returns them without writing business tables. It requires no `Idempotency-Key`. Retain loading, empty/error states, input validation, external timeouts, concurrency limits and necessary resource protection.

Return `providerId`, `providerQuoteId`, carrier/service, amount/currency and available expiry information. No persisted request, offer list, execution state, execution claim/version/takeover, failed-task recovery or quote-status endpoint remains. Failed or expired queries are new rate calls, not resumed tasks. Historical quote-list restoration is not guaranteed; reload may require re-querying. Browser drafts are convenience state only.

**Acceptance:** all quote success and exception paths write no business tables; input edits clear selection, and late responses cannot replace results for newer inputs.

### FR-03 Rate Comparison and Selection (P0; sort/detail P1)

Each shipping option displays carrier name, service, total price including taxes, currency, and estimated transit days when available. P1 displays optional base charge, surcharges, taxes, guarantees and features. Default sorting is lowest total price first. At most one option can be selected. The selection includes the exact carrier/service quotation, not only the carrier name.

**Acceptance:** select by the pair `(providerId, providerQuoteId)`. Editing shipment details invalidates the unsubmitted selection and requires new rates. Expiry information is a hint/admission aid, not a provider price guarantee; final validity is decided by the provider. A changed displayed price requires confirmation. Expiry must not erase an already-submitted unresolved operation.

### FR-04 Shipment Creation (P0)

Before final submission, show shipment route, package information, selected service and amount. The user explicitly confirms `providerId`, `providerQuoteId` and required shipping/display snapshots; no internal quote ID or `Idempotency-Key` is accepted. Save the local active operation before sending. Backend account/credentials and user scope are never browser-selected.

The backend validates identity/provider, returns an existing booking if present, otherwise validates new parameters and commits a unique PENDING booking before contacting the provider. Only the insert winner dispatches; a storage failure prevents dispatch. Concurrent conflicts return the original booking. Submitted inputs/prices are user snapshots, not provider-confirmed facts; actual provider information is stored separately when returned.

**Business boundary:** no user-to-provider-quote ownership proof or signed quote credential is added. Provider performs final quote validation. Distinct quotes may each create a booking even for identical shipping inputs; cross-quote duplicates are accepted. Verify ID scope/reuse before release and add backend account scope to uniqueness if required.

**Acceptance:** success requires a valid provider receipt; rejection/malformed results never appear successful. Same-identity create always returns existing state without retrying, including RETRYABLE. Unknown outcomes remain Pending Confirmation or Manual Review; never resend automatically.

### FR-04a Status Recovery and Safe Retry (P0)

- With bookingId, query booking status; without it, read-only lookup by providerId/providerQuoteId under the current user. Reads never create, retry or mutate status.
- No row means retain the quote identity and offer a user-initiated initial submission retry; it does not prove an earlier in-flight request cannot commit later.
- Only a separate explicit “Retry sending” action can resend a proven-unsent RETRYABLE booking, using saved submission facts and a single state/version claim winner. PENDING, unknown, manual-review and terminal states cannot resend.
- Refresh performs reads only. Preserve polling bounds, manual-review display and label behavior. A user may start another shipment while retaining query access to earlier unresolved local operations.
- Upgrade browser storage with account isolation and logout cleanup. Legacy bookingId remains usable; old internal quoteId is never a providerQuoteId. Records without a recovery identity require an explicit limitation/support message, not automatic creation.

### FR-05 Shipment Confirmation (P0)

On success show a success indicator, ShipID and an “Open Shipping Label” link or button, if the service returns a valid URL. Display tracking numbers and tracking link if returned. Offer “New Shipment.” Do not generate fake confirmation details in the real workflow. If creation is confirmed but its label is not yet available, retain the confirmed shipment and display a separate label-pending/unavailable state. The frontend may retrieve the label once for that existing shipment, then offer manual retry if it is pending. Background label processing and continuous label polling are deferred. Never recreate the shipment to obtain a label. A label lookup API is identified in the supplied integration reference; response and delayed-availability behavior require validation.

### FR-06 Exceptional States (P0)

| Condition | UI response |
|---|---|
| Missing/invalid form values | Inline validation |
| No rates | “No Rates Available”; edit and retry |
| Slow rate retrieval | Loading / progress state |
| Rate request failure | Plain-language error and retry option |
| Shipment creation in progress | Disable Create action; show processing |
| Confirmed creation failure | Explain result and next action |
| Uncertain shipment creation | “Pending Confirmation”; do not claim failure or resend automatically |
| Duplicate completed submission | Show existing result rather than creating another shipment |
| Shipment created, label not yet available | Keep creation confirmed; retrieve the label separately without creating another shipment |
| Shipment edited, quote expired or price changed | Invalidate or refresh the quote; require explicit confirmation of a changed price |

### FR-07 Account and Provider Access Configuration (P0)

Retain the existing account login, session/fingerprint validation, account isolation, same-origin protections and quotas. Resolve userId from login, not a body/header identity assertion. Provider account and credentials are chosen on the backend independently of application login. Switching accounts must not display another user's local operations; logout clears local account state. No registration/user-management expansion is required. Secrets remain local and excluded from Git and browser data; missing configuration produces actionable non-secret errors.

## 5. UI/UX Requirements

**Visual style:** clean, professional logistics SaaS; desktop-first, responsive, accessible labels, clear action hierarchy, legible currency and transit details.

**Navigation:** three-step indicator — `1 Shipping Details`, `2 Compare Rates`, `3 Confirmation`. The third step has a review-before-creation state and a result state.

### Screen A: Shipping Details
- Header and progress indicator
- Side-by-side sender and recipient panels (stack on mobile)
- Package details panel
- Primary action: **Get Shipping Rates**
- Field errors and loading indicator

### Screen B: Compare Rates
- Origin/destination and package summary
- Price-sorted selectable cards
- Carrier, service, total cost, transit days
- Expandable charge breakdown (P1)
- **Back / Edit** and **Continue**

### Screen C: Review & Confirmation
- Before creation: route, package, chosen carrier/service and price; **Create Shipment** button
- After creation: success status, ShipID, tracking if available, shipping label link and **New Shipment** action
- Failure/unknown outcomes use explicit distinct messages

## 6. Business Rules

| ID | Rule |
|---|---|
| BR01 | Sender and recipient must be valid |
| BR02 | Package measurements must be greater than zero |
| BR03 | Initial demo supports one Canadian domestic package across all provinces and territories |
| BR04 | Prices use returned currency and total charge, including taxes |
| BR05 | Selection identity is providerId + providerQuoteId, with carrier/service display |
| BR06 | Booking saves submitted shipping/display snapshots; it does not prove their association with the provider quote |
| BR07 | Same user/provider/quote yields one booking; different quotes may independently create shipments |
| BR08 | An uncertain creation outcome is distinct from confirmed failure |
| BR09 | Label access is offered only when a valid URL exists; a pending label does not invalidate confirmed shipment creation |
| BR10 | Changed shipment details or an expired quote require new rates; changed prices require explicit confirmation |
| BR11 | External integration credentials belong to local backend configuration, not application login |
| BR12 | Quote retrieval writes no business records and has no durable task/status recovery |
| BR13 | Status recovery is read-only; only explicit RETRYABLE retry can resend a saved booking |
| BR14 | Expiry does not remove booking identity, uncertainty, history or duplicate protection |

## 7. Product Acceptance Journey

1. Enter valid Canadian addresses and package dimensions.
2. Retrieve multiple shipping rates.
3. Compare services, prices and estimated delivery times.
4. Select one service.
5. Review and confirm the shipment.
6. Receive ShipID and access the generated label, retrieving it separately if not immediately available.
7. Demonstrate validation, empty/error conditions and accidental duplicate submission prevention.

## 8. Product Assumptions / Open Questions

- UI choice: HTML + Bootstrap 5 + Vanilla JavaScript, served by the backend; no separate frontend design document.
- Identity: existing authenticated account; booking ownership comes from backend login context, not provider quote ownership proof.
- Shipping scope: all Canadian provinces and territories, one package, Imperial defaults.
- Duplicate safety and uncertain-outcome handling are confirmed P0 requirements.
- External access configuration stays local to the backend; no new registration system is in scope.
- Quote preselection remains an open product preference; explicit selection is the current recommendation.
- Required contact/address fields are preliminary and may be revised from observed API validation.
- The rules for invalidating/refreshing quotations are confirmed; provider expiry signals, shipping-date restrictions and ambiguous-creation lookup capabilities still require verification.
- The supplied integration reference identifies a separate label lookup API; its behavior remains to be validated. Shipment confirmation and label availability must remain distinct.
- Provider quote ID uniqueness scope, case/length rules, reuse period and required creation fields are release gates. If IDs are only account-unique, include a stable backend account identifier in the booking uniqueness/lookup contract. No contract verification is claimed by this document update.
- **The included HTML prototype is mock-only and does not make API calls or create shipments.**

## 9. Deliverables and Document Boundaries

Product deliverables: three-step user experience, functional quotation comparison, shipment creation, confirmation and failure states. Target backend boundaries, booking deduplication, API mappings and historical-data migration are specified in the [backend design](../design/backend-design.md), [API contract](../design/application-api.md) and [I10 plan](../iterations/iteration-10-stateless-quotes.md). The frontend should use the simplest practical implementation to exercise the complete journey; a separate frontend technical design document is not required.

On 2026-11-16 the user authorized implementation and removed historical-data/old-table migration from scope. Use a new two-business-table baseline with booking-owned snapshots; do not automatically clear or upgrade an existing database. Incompatible applied checksums fail startup. Browser legacy-storage upgrade remains required, but no historical database import is promised. Coordinate backend/frontend versions and keep release separate from implementation.

Design framing: describe the backend as a new, self-contained system without assuming existing modules are implemented. During development, existing code may be reused after it is checked against the target requirements; reuse does not waive acceptance.
