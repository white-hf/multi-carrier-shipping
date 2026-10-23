# Iteration 01 — Implementation record

Status: Verified via automated API/SQLite tests and Playwright E2E browser journey against a controlled local HTTP provider stub.

Implemented the full Canadian shipping input, fixed backend user context, immutable shipment-request storage and exact quote selection. The product uses a separate SingleProviderRateEngine behind RateComparisonPort; the legacy fan-out engine implements that contract in its separate deployment configuration. Provider/account aliases and quote identities remain distinct. Quotes retain exact CAD minor-unit amounts and conservative expiry; the browser receives only internal selection IDs.

The supplied frontend is integrated as locally served HTML, Bootstrap 5 CSS and Vanilla JavaScript. Added missing label markup, optional contact inputs and backend field-error mapping; retained operation identity across uncertain responses. No private credentials or unused response sample are published.

Verified in `WorkflowApiTest`: all 13 Canadian provinces/territories, imperial/metric/mixed unit conversion without binary float precision loss, past shipping-date rejection, inline field error mapping, empty (`NO_RATES`) and upstream error states (`RATE_FAILED`), price/transit sorting, keyboard selection, and selection invalidation on form edits.

Independent acceptance follow-up (2026-10-23): I1 remains accepted. The current validation commands and counts are in the [acceptance report](acceptance-test-report.md); browser execution is separate from the backend suite.
