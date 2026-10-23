# Shipping workflow — acceptance report

Date: 2026-10-23  
Status: **Accepted for the agreed demo scope — I1, I2 and I3** after independent review follow-up.

## Reproducible validation

| Command | Result | Scope |
| --- | --- | --- |
| `./mvnw clean test` | 123 passed; zero failures, errors or skips | Backend unit tests, real SQLite persistence, concurrency and local HTTP provider integration |
| `npm ci` followed by `npm run browser:install` | Passed | Lockfile-based Playwright installation and matching Chromium |
| `npm run test:browser` | 1 passed; zero failures, errors or skips | Eight-stage browser journey against the running application and local provider |

The 123 backend cases include 32 `WorkflowApiTest` cases, four `ShipmentRestartTest` cases and 87 existing regression cases. The browser journey is deliberately separate from the default Maven suite; it is not included in the backend count. The previous report's 103 tests described the earlier combined suite.

All current automated checks use synthetic accounts and local provider stubs. They require no sandbox credentials and create no external shipments. See [workflow setup](../runbooks/workflow-setup.md) for prerequisites and exact commands. Maven XML/text results are generated under `target/surefire-reports/`. Archive backend reports before the browser run if retaining both runs: the browser suite replaces the `WorkflowApiTest` report.

## Iteration acceptance

| Iteration | Evidence | Decision |
| --- | --- | --- |
| I1 — Enter details and compare rates | All 13 Canadian regions; imperial, metric and mixed units; validation before provider dispatch; durable sorted quotes; hidden provider quote token; empty/error states; browser sorting, keyboard selection and edit invalidation | Accepted |
| I2 — Create safely and obtain a label | Durable claim before dispatch; same-key and different-key/same-request concurrency; conflicting intent and expired quotes; rejection and uncertain outcomes; independent delayed-label lifecycle; new malformed-success, dropped-response, actual-timeout and restart checks below | Accepted |
| I3 — Verify and prepare delivery | Independent rate/create circuit behavior; ownership and same-origin checks; redacted configuration; metrics and indexed query plans; migration checksums; pinned browser dependencies; documented setup and limitations | Accepted |

## Review findings closed

| Finding | Change and verification |
| --- | --- |
| Boolean or otherwise malformed shipment ID could become confirmed | Adapter requires a positive signed 64-bit shipment identity, encoded as a JSON integer or decimal-digit string. Twelve invalid response variants remain `PENDING_CONFIRMATION`; three valid identity variants confirm. Replay and discovery never create again. |
| Lost response and timeout were not demonstrated for the new workflow | `acceptedShipmentWithLostResponseDoesNotResubmit` consumes the creation request then closes without HTTP headers. `acceptedShipmentWithActualTransportTimeoutDoesNotResubmit` withholds the response beyond the configured 500 ms test deadline. Both retain one provider submission, replay the same booking and register one paused manual-review task. |
| Restart and failed confirmation persistence were not demonstrated for the new workflow | `ShipmentRestartTest` closes/reopens the file-backed workflow database and reconstructs services after interruption following claim, response loss after acceptance, or a failed confirmation write. Both same-key replay and a competing key retain the original booking without dispatch. Discovery deduplicates paused tasks. Confirmed receipts also survive reopening and replay. |
| Browser runner depended on one machine's npm/Chromium cache | Pinned Playwright in `package.json` and `package-lock.json`; checked-in `tests/browser/workflow-journey.cjs`; separate Maven `browser-tests` profile; bounded process wait and captured failures. Verified through the documented dependency/browser installation and execution commands. |
| Delivery status contradicted the acceptance report | Delivery plan, documentation index, iteration summaries and operating instructions now identify the accepted scope and separate current local evidence from historical sandbox observations. |

## Browser journey

The browser acceptance case covers default backend mode; validation and input retention; provider errors and no rates; sorting, keyboard selection and edit invalidation; quote expiry; explicit rejection; uncertain outcome across reload without resubmission; and confirmed creation with one automatic label read followed by manual retry to obtain a PDF. Label retrieval never calls shipment creation.

## Historical external sandbox observations

The preceding implementation report recorded live rate retrieval (18 quotes, approximately 13.7 seconds), quote-only shipment creation (approximately 17.1 seconds), PDF label retrieval (approximately 76 KB), idempotent replay and same-request duplicate rejection. These are historical observations supplied with the implementation, not fresh measurements from this review follow-up. They are not throughput benchmarks or latency guarantees. No additional external shipment was created during closure of the findings.

## Limits and operating posture

- Authoritative lookup by client reference is not established for the configured provider API. Unknown creation outcomes remain unresolved and require manual review; paused tasks never authorize automatic resubmission, cancellation or refund.
- Restart validation reopens SQLite and reconstructs the application services. It does not simulate an operating-system power failure, kill a running JVM, or validate production failover.
- Rate/create circuit separation is tested. Sustained-load behavior, saturation limits, P95/P99 latency and production cluster/shard operation are not established by this acceptance suite.
- SQLite, the configured demo user and local deployment remain the agreed demo posture. There is no production authentication or implemented database sharding.
- Frontend assets need no Node build. Node and Playwright are only required for browser acceptance testing.
