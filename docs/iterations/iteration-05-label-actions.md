# Iteration 05 — Shipping label actions

Status: Accepted for the agreed demo scope on 2026-10-23.
Scope: Extend FR-05 label access; preserve the I1–I4 creation, recovery and guest-token authentication safeguards.

## Outcome

A confirmed shipment has a clear label panel with View, Download, Print, Email and Share actions. Confirmation does not imply label readiness. Actions remain visible but unavailable until a real label is retrieved. Pending/error labels retain an explicit retry action; no background polling or new creation call is introduced.

## Delivery checklist

- ST-501: Change the success heading to Shipment confirmed and expose label readiness independently, including honest demo-mode guidance.
- ST-502: Reuse the existing read-only label endpoint. Retain PDF bytes in browser memory for download and supported native file sharing; open the PDF viewer for viewing/printing. A URL-only provider response opens its viewer for manual saving/printing when bytes cannot be read locally.
- ST-503: Email opens the user's mail application with shipment context; the user attaches the downloaded PDF manually. Never claim an attachment was sent or share a browser-local blob URL. Native file sharing is capability-detected; unsupported browsers fall back to downloading/opening the label with guidance. No backend email delivery, public hosting or new API is added.
- ST-504: Verify pending, PDF-ready, URL-only, retrieval failure, reset/reload, demo mode and sharing fallbacks in browser tests. Keep exactly one original provider creation; all label actions reuse that booking.

## Implementation boundaries

Preserve current formatting, guest-token authentication headers and frontend styling. Revoke object URLs when replacing/resetting a label. Keep files in memory, not browser storage. Refresh requires an explicit Reload label if its previous automatic attempt was already made. An expired provider link can be retrieved again for the same shipment.

## Acceptance

Local provider/browser tests demonstrate ready/disabled controls, PDF download bytes, viewer/print navigation, mailto draft without blob links, native file-share invocation/cancellation and download fallback, and no additional create call. Backend API regression verifies existing PDF/URL contracts and guest token authentication. No new chargeable sandbox creation is needed. Browser tests can verify dispatch to viewer/OS actions, but cannot assert a physical printout, delivered email or OS share completion.

## Execution result

ST-501–504 are complete. The backend suite passed 135 tests; the separate browser journey passed pending/error/PDF-ready/URL-only/demo states, byte-for-byte PDF download, print-viewer dispatch, email draft content, native sharing invocation/cancellation, download fallback and reload/reset behavior. All checks used local fixtures and preserved one original provider creation. See [I5 summary](../summaries/iteration-05-label-actions.md).
