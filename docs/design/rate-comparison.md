# Rate comparison: review contract

Status: review criteria applied to the Java 17 demo. Build and 14 tests pass; see [validation evidence](../summaries/java17.md). Account-specific pricing and production carrier contracts remain out of scope.

## Proposed flow

Resolve account context → validate shipment → select eligible adapters → collect concurrently within a deadline → normalize valid results → sort comparable quotes → return quotes plus carrier coverage.

## Contract decisions to confirm against code

- Request: origin, destination, packages, explicit measurement units, ship date, currency, and shipment options.
- Quote: carrier/service identifiers, decimal amount, currency, known charge components, estimate flags, delivery estimate, and validity metadata when provided by the carrier.
- Response: correlation ID, quotes, attempted carrier outcomes, and completeness. A partial response is successful only according to a documented API contract; it must disclose missing coverage.
- Failure: distinguish invalid input, valid request with no eligible services, partial dependency failure, and total dependency failure. Specify HTTP mapping after reviewing the current API.
- Ranking: compare supported same-currency totals with equivalent charge inclusion; use a deterministic tie-breaker. Do not silently rank mixed currencies or manufacture tax values or expiry times.

## Review checklist

1. Correctness: units, positive weights/dimensions, package count, money precision and rounding, service eligibility, deterministic ranking, and malformed carrier results.
2. Concurrency: no sequential waits that accumulate per-carrier timeout budgets; bounded threads, queues, HTTP connections, and outbound admission; no executor leaks.
3. Resilience: overall and per-call deadlines, actual transport cancellation behavior, quota handling, and bounded retries only where safe and useful within the remaining budget.
4. Isolation: carrier credentials and cache entries scoped to the resolved account; no secret-bearing payloads in source control or logs.
5. Maintainability: common adapter interface, focused domain/application/infrastructure boundaries, and configuration with meaningful defaults.
6. Observability: safe correlation, dependency outcomes, timing, and capacity signals.

## Required demonstration evidence

| Scenario | Expected result |
| --- | --- |
| Multiple successful carriers | Correctly normalized and deterministically ranked quotes |
| Fast result plus slow carrier | Available quotes survive; request finishes within its budget with documented tolerance |
| One adapter throws or returns malformed data | Other valid quotes remain usable; failed carrier disclosed |
| All carriers fail | Explicit dependency failure, not a misleading empty successful comparison |
| No eligible services | Valid no-results outcome distinguished from outage |
| Invalid package | Rejected before outbound work |
| Mixed currency | Explicit rejection/filtering or documented conversion; no raw numeric comparison |
| Saturation | Bounded resources and documented rejection/degradation |
| Account-specific pricing/cache | One account cannot receive another account's rates |

Use deterministic fake carriers or a local mock server for repeatability. Timing tests need controlled delays and realistic scheduling tolerance. Run relevant build/tests and record exact commands, environment, outcomes, and limitations. Performance improvements need a reproducible baseline; do not claim speedups from code inspection alone.
