# Feature structure refactor validation

Validated October 20, 2026 with Java 17. All 38 tests pass: 15 quote tests and 23 booking tests, with no skipped tests. The original 36 tests remain; two new regressions cover old booking fingerprints/persisted claims and quote metrics failure isolation.

The original compiled implementation generated the checked-in canonical request and SHA-256 fixtures. The refactored code produces identical bytes/hash, and replaying a claim carrying those stored values makes no carrier submission. No schema migration is required.

The extracted worker gauge retains its supplier, preventing an expired weak reference from producing NaN. The existing real-HTTP timeout test verifies the worker count returns to zero and connection capacity recovers.

A packaged-process check confirms three rate offers, 12 same-key concurrent booking requests producing one booking and one submission, changed-intent conflict, terminal rejection, timeout-after-accept handling, inquiry recovery in a new JVM, and Prometheus booking metrics. [Recorded outcomes](structure-smoke-results.json).

Import checks found no quote-to-booking imports, booking-to-quote imports, shared-to-feature imports, domain framework imports, or application-to-infrastructure/Micrometer imports. Documentation links and XML/SVG parsing were checked.

[Package design](../design/code-organization.md) · [Booking runbook](../runbooks/booking-demo.md)

These checks use simulated carriers and local SQLite files; they do not establish production carrier behavior or throughput.
