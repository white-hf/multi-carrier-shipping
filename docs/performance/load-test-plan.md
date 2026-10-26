# Local load and database-scale test plan

Date: 2026-10-24
Status: Execution completed on 2026-10-25 against `78c729e`; SQLite-only evidence. See [results](load-test-results.md).

## Scope

Run on the local Mac (16 GiB RAM, 10 logical CPUs) with SQLite only. Three independent dimensions: database scale (LT-DB), concurrent correctness/write contention (LT-R), and HTTP load with a local provider stub (LT-HTTP). Do not contact the sandbox, reuse local credentials, edit frontend code or open the demonstration database.

## Dataset and safety

| Stage | Requests | Quotes | Bookings | Recovery tasks |
| --- | ---: | ---: | ---: | ---: |
| Small | 10,000 | 50,000 | 5,000 | 500 |
| Medium | 100,000 | 500,000 | 50,000 | 5,000 |
| Large | 1,000,000 | 5,000,000 | 500,000 | 50,000 |
| Stress | 3,000,000 | 15,000,000 | 1,500,000 | 150,000 |

Grow one isolated file outside the repository. Use synthetic addresses, multiple users, UUID-shaped time-ordered identities, variable quote counts including zero, historical dates, expired quotes and a minority of unresolved bookings. Seed all existing indexes; keep WAL, FULL synchronous mode and foreign keys enabled. A bounded 64 MiB seeder cache is preparation-only; measurements use the application's datasource settings. Record Python seeder and JDBC SQLite versions separately.

Stop safely before any batch if free disk is below 15 GiB or total run artifacts exceed 22 GiB. Checkpoint between stages; do not copy/VACUUM the full dataset. Do not clear macOS caches or alter global machine settings. Monitor database/WAL size and report build time; impose a two-hour maximum seed stage. Record failures rather than silently weakening durability or dropping indexes.

## LT-DB: exact business access patterns

Use production JDBC stores for request/booking idempotency, request/booking/quote lookup, request quotes, overdue booking discovery and conditional booking updates. Record the actual schema and JDBC SQLite version. Explain the corresponding SQL using current projections and predicates. Do not assert exact query-plan strings or require a particular generated index name.

Current indexes: request/booking `(user_id,idempotency_key)` uniqueness; booking `(user_id,request_id)` uniqueness; quote `(user_id,request_id)`; booking `(status,submission_deadline_at,booking_id)`; task `(state,available_at,task_id)` and `(user_id,booking_id)` uniqueness.

Recovery-task due scans are synthetic index probes: the workflow currently registers paused manual-review tasks and has no version/lease task consumer. Legacy recovery schemas are outside this test. Temporary sorting on booking discovery is reported and measured, not automatically treated as failure.

For each stage, record first-pass results separately from warmed samples. Reopening a connection is not cold OS cache. Warmed point/read measurements use randomized hits/misses, ownership mismatches and three repetitions of at least 1,000 operations per scenario. Collect P50/P95/P99, throughput, sample count, returned rows, errors and query plans. Repository latency includes pool checkout and row mapping; measure standalone checkout separately rather than subtracting incompatible percentiles.

Initial warmed P95 budgets: point lookups 10 ms, request quotes 20 ms, discovery of 100 rows 50 ms. Report all failures and scaling trends. These provisional local budgets do not define an HTTP SLA.

## LT-R: contention and correctness

At each dataset stage run 1, 2, 4, 8 and 16 workers for five measured seconds per level, using the production four-connection pool. Mix point reads with conditional writes to an unresolved booking. Count successful CAS, stale-version losses, pool/SQLite failures, throughput and latency. CAS conflicts are expected; uncaught errors, invalid state, or unbounded waiting fail acceptance. Include commit time in mutation latency.

At HTTP level verify same-key concurrent rates/booking submissions, payload conflicts, failed-rate retry and uncertain creation without automatic resubmission. Assert durable row counts and provider call counts, not only HTTP statuses.

## LT-HTTP: local application and provider

Run a separate workflow application with random loopback HTTP/management ports and a separate small database. Keep guest authentication enabled. Use bounded local stub latency, disabled creation retry and isolated rate/create capacity. Collect status distributions and actual provider call counts.

Exercise status reads, fresh rates and complete rate-to-booking workflows at 1, 2, 4, 8 and 16 workers. Three ten-second repetitions per level after warmup. Report endpoint latency separately from workflow latency, achieved throughput, errors, process CPU/heap and file sizes. This closed-loop test describes concurrency scaling, not an open-loop arrival-rate capacity limit. Explain expected capacity rejection under saturation and verify post-load recovery.

Run LT-HTTP once at Medium; LT-DB and LT-R run at every scale. Avoid an unnecessary cross-product of HTTP and four large seed datasets. Full multi-process/multi-host load and long soaks are out of scope.

## Delivery

Commit reusable scripts/harnesses and a concise report with environment, commands, actual stage outcomes, residual issues and raw JSON/log locations. Keep databases and bulk raw output outside Git. Stop later scale stages when safety/correctness gates fail; record the reason and remaining scope. Do not claim unexecuted stages passed.
