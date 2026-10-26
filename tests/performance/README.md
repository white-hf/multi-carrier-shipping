# Local shipping performance tests

Run from the repository root with Java 17, Maven wrapper and Python 3 available:

```sh
./tests/performance/run.sh /tmp/shipping-load-my-run 100000
```

The last argument is the maximum request count: `10000`, `100000`, `1000000` or `3000000`. Use a new output directory for each run. The default is the complete sequence through 3M requests. Large files remain outside Git. No `.env` file or sandbox account is required.

The runner builds the current application, extracts its runtime dependencies, compiles the standalone harnesses, seeds an isolated database and runs production JDBC repository probes at each size. At Medium it also starts the real application and a local provider stub on random loopback ports. It never opens the demonstration database. Production source and frontend files are not modified.

Data preparation maintains all indexes and uses WAL/FULL/foreign keys plus a bounded 64 MiB preparation cache. Repository measurements use the application's four-connection datasource and default SQLite cache. The Python seeder and application's SQLite driver versions are recorded separately.

The run stops before a seed batch when free storage is below 15 GiB, artifacts exceed 22 GiB, or a seed stage exceeds two hours. Correctness failures stop subsequent stages. Query latency budget failures are recorded for analysis; they do not silently alter indexes or database settings. HTTP capacity rejection is reported separately from successful responses.

Artifacts include `seed-results.json`, `database-<size>.json`, database logs, HTTP progress/results and `progress.jsonl`. The HTTP log includes deliberate connection-loss fault injection; assess assertions and JSON outcomes rather than treating the injected warning as an unexpected failure. First-pass data is not a claim of cold operating-system cache. The closed-loop HTTP client shares the host/process with the server and stub, so results are local regression evidence, not production capacity guarantees.

See [the execution plan](../../docs/performance/load-test-plan.md) for workload details and acceptance budgets. Normal unit and browser tests do not automatically run these load harnesses.

The final dataset also receives a separate CAS latency probe and query-plan audit. Reusable tooling produces a Markdown report inside the run directory automatically; index allocation comes from the JDBC result instead of a duplicate Python page walk.
