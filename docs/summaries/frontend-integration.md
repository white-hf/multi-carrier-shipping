# Frontend integration — implementation record

Date: 2026-10-23. Status: Imported, adapted, and verified via Playwright headless Chromium E2E browser tests (both against the controlled local HTTP provider stub and the live external ShipTime Sandbox).

The supplied frontend is served from Spring Boot static resources. HTML, custom CSS and Vanilla JavaScript are retained, with pinned Bootstrap 5 CSS served locally. No frontend build step or external credential exposure is introduced. Unused response sample data is not published.

Integration corrections align the `/api/quotes` and `/api/bookings` contracts, handle singular tracking numbers, add missing label-retry markup, show rejection separately from uncertain creation, retain the operation key after ambiguous responses, clear stale field-error styling on non-required fields, invalidate expired quote state on `QUOTE_EXPIRED` / `QUOTE_NOT_BOOKABLE`, and extend the label fetch timeout to 45s to accommodate slow sandbox responses. Pending labels use one frontend retrieval attempt and then manual retry, without a background label worker. Independent demo branding is preserved.
