# Iteration 03 — Delivery verification

Status: **Accepted for the agreed demo scope** after independent review follow-up on 2026-10-23.

The integrated static frontend and backend use unversioned application endpoints, immutable quote identities, durable creation claims and independent on-demand labels. The fan-out simulator remains a separate profile.

Validation:

- `./mvnw clean test`: 123 passing backend cases, including new workflow lost-response, actual-timeout, malformed-success and database-reopen coverage.
- `npm ci` and `npm run browser:install`: reproducible installation of the pinned Playwright dependency and matching Chromium.
- `npm run test:browser`: one passing eight-stage browser journey, separate from the backend count and requiring no sandbox credentials.
- Existing concurrency, circuit separation, metrics, ownership, same-origin, migration and query-plan checks remain passing.

The [acceptance report](acceptance-test-report.md) maps evidence to I1/I2/I3 and records historical sandbox observations separately. The [runbook](../runbooks/workflow-setup.md) documents setup and test commands. Uncertain provider outcomes require manual review; no production load, OS crash, cluster failover or sharding validation is claimed.
