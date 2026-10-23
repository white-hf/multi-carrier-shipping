# Iteration 01 — Durable quotation comparison

Status: Verified against controlled local HTTP provider stub (API + Playwright E2E). Requirements: FR-01–03, FR-07; ST-001–003, ST-101–103, ST-401.

Deliver a complete rates API using full Canadian shipment details, the configured demo user, one sandbox-provider engine, durable request/quote association, exact money and expiry. The separate frontend engineer owns the UI; integrate its files and verify the browser journey when available.

Implement domain validation, quote-owned storage and selection contract before booking integration. Share concrete unversioned application API examples with the frontend engineer. Test conversion, ownership, empty results, timeouts, saturation, provider mapping and restart persistence using HTTP stubs. No automated test creates an external shipment.

Exit: backend tests pass and the frontend demonstrates actual rates, errors and selection. Until frontend delivery, API verification is partial iteration evidence, not end-to-end completion.

Browser automation: use Playwright against a controlled HTTP provider stub. Cover successful flow, invalid inputs, expiry, duplicate submissions, uncertain outcomes and delayed labels. Keep external sandbox checks separate and deliberate.
