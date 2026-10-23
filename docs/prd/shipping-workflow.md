# Multi-Carrier Shipping — Rate Comparison & Shipment Creation

**Product Requirements Document | Version 1.4 | Product Decisions Updated; Integration Validation Pending**  
**Context:** Independent shipping web application — product iteration  
**Product:** Web application

## 1. Product Overview

Build a simple web application that enables a shipping operator to enter sender, recipient, and package details, compare real-time shipping services from multiple carriers, select a quote, and create a shipment with an accessible shipping label.

**Core journey:** Shipping Details → Compare Rates → Review & Confirm → Shipment Confirmation.

**Goals:** clear entry and validation; transparent comparison of cost and transit time; guided shipment creation; user-friendly handling of failures and uncertain outcomes. No sign-in is required for the demo; a fixed backend-configured demo user owns the workflow.

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
| F12 | Duplicate safety | Prevent repeated side effects and return the existing operation on retries |
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

“Get Shipping Rates” starts an idempotent quotation request (`Idempotency-Key`). Show a loading state while rates are being retrieved. Quotes must be derived from the shipping details and must not be fabricated in the working product. Show an empty state if none is available, and errors when retrieval fails. Allow the user to revise shipment information.

- **Idempotent request & retryable execution:** Repeating the same rate request with the same key and identical shipment details reuses the persisted shipment request rather than creating duplicate records. Failed, timed-out or expired rate executions can be retried under the same key and request identity; edited shipment details start a new request with a fresh key.
- **Durable business record vs. browser draft trade-off:** The backend persists `shipment_requests` independently of third-party providers for customer attribution, support traceability and provider-outage resilience (accepting PII storage, retention and schema maintenance responsibilities). A 24-hour browser draft (`localStorage`) improves navigation and reload recovery (restore inputs and active rate key), but is convenience state subject to clearing, expiry and shared-device exposure, never a substitute for the backend record.

**Acceptance:** multiple quotes can be shown with accurate association to the input shipment; failed or expired rate lookups can be retried under the same request identity without duplicating the shipment request.

### FR-03 Rate Comparison and Selection (P0; sort/detail P1)

Each shipping option displays carrier name, service, total price including taxes, currency, and estimated transit days when available. P1 displays optional base charge, surcharges, taxes, guarantees and features. Default sorting is lowest total price first. At most one option can be selected. The selection includes the exact carrier/service quotation, not only the carrier name.

**Acceptance:** user can compare and select a quote; selected card is visually distinct; Continue uses the selected quote. Editing shipment details invalidates the selected quotation and requires new rates. An expired quotation must be refreshed; any changed price requires explicit user confirmation before creation.

### FR-04 Shipment Creation (P0)

Before final submission, show shipment route, package information, selected service and total amount. The user clicks “Create Shipment.” Display a pending state and prevent accidental repeated submission. Shipment creation uses the original shipment information associated with that exact selected quotation.

**Acceptance:** successful creation presents an actual confirmation; failures never display false success. An uncertain outcome first appears as Pending Confirmation and never automatically triggers a duplicate operation. If safe provider recovery is unavailable, show Manual Review Required with the booking reference and guidance to contact support; stop polling and hide retry/recreation controls for that operation. Refresh preserves this state. Any replay retains the same operation identity rather than starting another shipment.

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

### FR-07 Demo Access Configuration (P0)

No application registration or login UI is required. Use a fixed backend-configured demo user for ownership and duplicate protection; the frontend may display this identity. In a future authenticated deployment, resolve the user ID from the logged-in principal instead. External integration access is configured on the backend, independently of application-user identity. Access settings remain local and excluded from version control; no secret values or private access details belong in tracked documents or the browser. A demonstrator must be able to supply the required settings and start the integration with a short, clear setup process. Missing settings should produce an actionable configuration error.

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
| BR05 | Selection is specific to a carrier + service + quotation |
| BR06 | Shipment creation reuses the details underlying the selected quote |
| BR07 | Confirmed duplicate submissions must not create another shipment |
| BR08 | An uncertain creation outcome is distinct from confirmed failure |
| BR09 | Label access is offered only when a valid URL exists; a pending label does not invalidate confirmed shipment creation |
| BR10 | Changed shipment details or an expired quote require new rates; changed prices require explicit confirmation |
| BR11 | External integration credentials belong to local backend configuration, not application login |

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
- Identity: fixed backend-configured demo user without login; production ownership comes from authenticated user identity.
- Shipping scope: all Canadian provinces and territories, one package, Imperial defaults.
- Duplicate safety and uncertain-outcome handling are confirmed P0 requirements.
- No application login is needed; external access configuration stays local to the backend.
- Quote preselection remains an open product preference; explicit selection is the current recommendation.
- Required contact/address fields are preliminary and may be revised from observed API validation.
- The rules for invalidating/refreshing quotations are confirmed; provider expiry signals, shipping-date restrictions and ambiguous-creation lookup capabilities still require verification.
- The supplied integration reference identifies a separate label lookup API; its behavior remains to be validated. Shipment confirmation and label availability must remain distinct.
- **The included HTML prototype is mock-only and does not make API calls or create shipments.**

## 9. Deliverables and Document Boundaries

Product deliverables: three-step user experience, functional quotation comparison, shipment creation, confirmation and failure states. Backend technical architecture, database schema, API request mappings, idempotency implementation and test architecture will be discussed first and then specified in a **separate Technical Design Document**. The frontend should use the simplest practical implementation to exercise the complete journey; a separate frontend technical design document is not required.

Design framing: describe the backend as a new, self-contained system without assuming existing modules are implemented. During development, existing code may be reused after it is checked against the target requirements; reuse does not waive acceptance.
