# Iteration 05 — Shipping label actions

Date: 2026-10-23
Status: **Accepted for the agreed demo scope.**
Scope: ST-501–504; FR-05 confirmation and label access.

## Delivered

The success heading is now **Shipment confirmed**. A dedicated label panel shows View, Download PDF, Print, Email and Share controls; they are unavailable while a label is pending or in demo mode. Creation and label readiness remain independent. Retry/reload reads the existing shipment only and retains the 2-hour fingerprint-bound Guest Token headers.

PDF responses become an in-memory `File` and object URL. Download preserves its bytes and uses a shipment-specific filename. View opens the PDF; Print opens the viewer with printing guidance. Object URLs are revoked on replacement/reset; files and blob URLs are never persisted in browser storage. After refresh the user explicitly reloads the label.

Email opens a mailto draft; the user chooses recipients and attaches the downloaded PDF. Native file sharing is capability-detected. Cancellation does nothing; unsupported/failed sharing falls back to downloading the file. A delayed share failure cannot act on a different booking. No browser-local blob URL is emailed or shared as a public link.

For provider-hosted URLs, View/Download/Print use the provider viewer for saving and printing, with explicit guidance. This avoids assuming cross-origin PDF reads are permitted. Sharing requires saving that PDF first. No new backend API, email service, hosting or shipment creation behavior was added. Existing frontend formatting and styling were retained with a small label-panel addition.

## Verification

- `./mvnw clean test`: **135 passed**, zero failures, errors or skips.
- Existing label API integration case passed for pending, PDF and allowlisted URL responses without another creation.
- `npm run test:browser` (`./mvnw test -Pbrowser-tests`): **one extended journey passed**, including 2-hour guest token issuance, disabled pending/demo actions, retrieval failure, PDF-ready actions, byte-for-byte download against a valid non-sensitive PDF fixture, print-viewer dispatch, encoded mailto draft without blob links, native file-share invocation/cancellation, unsupported-share download, explicit reload and URL-only viewer fallback.
- Throughout label-action checks the provider creation count remained one; local PDF actions reused existing bytes rather than fetching or creating another shipment.

Tests use synthetic local accounts and a generated one-page TEST SHIPPING LABEL fixture. No real sandbox shipment was created. Native printing, external mail delivery and OS share completion require manual browser/OS acceptance; the automated suite verifies their dispatch/contracts, not physical delivery. Email requires a configured mail application and manual attachment. File sharing support varies by browser.
