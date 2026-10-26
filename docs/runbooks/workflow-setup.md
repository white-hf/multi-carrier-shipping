# Shipping workflow — local setup

Status: Accepted for the agreed demo scope. Backend and browser verification are separate reproducible commands.

Use Java 17 and the Maven wrapper. Export the environment settings in your shell before starting; Spring does not load a `.env` file automatically. No Node build is required for the frontend. Select the `workflow` Spring profile for the new single-provider product flow; the `demo` profile remains a separate fan-out/failure simulator.

Configure backend environment values locally (never commit their values):

- `SHIPPING_PROVIDER_BASE_URL`: configured integration host; HTTPS except local HTTP test servers.
- `SHIPPING_PROVIDER_USERNAME` and `SHIPPING_PROVIDER_PASSWORD`: external Basic Auth credentials.
- `SHIPPING_PROVIDER_SHIPMENTS_PATH`: deployment-specific shipment resource path; verify the prefix/trailing slash against the supplied integration reference.
- `SHIPPING_PROVIDER_LABEL_HOSTS`: comma-separated exact HTTPS hosts allowed for returned label URLs. Empty means no external redirect is allowed.
- Optional `SHIPPING_DATABASE_PATH` (default `./data/workflow.db`) and `SHIPPING_DEMO_USER_ID` (default `demo-user`).

For a guided browser experience, see [manual startup and acceptance](manual-acceptance.md). The version-controlled launcher is `./scripts/start-workflow.sh`; it loads the ignored local `.env`.

## Run and verify

Backend unit and API integration tests require Java 17 only:

```sh
./mvnw clean test
```

Browser acceptance additionally requires Node.js 20+ and npm. Install the locked dependency and its matching browser once, then run the journey:

```sh
npm ci
npm run browser:install
npm run test:browser
```

The Maven `browser-tests` profile selects the browser-tagged test. The default Maven test command excludes it, so backend tests do not require Node or Chromium. The browser runner is [workflow-journey.cjs](../../tests/browser/workflow-journey.cjs); it resolves the repository's pinned Playwright dependency. Browser binaries are installed by Playwright rather than selected from a machine-specific cache path. Linux CI may additionally need `npx playwright install --with-deps chromium` to install operating-system libraries.

The browser test starts the application and local HTTP provider automatically with temporary SQLite state. No running sandbox application, credentials, or external shipment creation is required. Maven results are in `target/surefire-reports/`; run the two suites separately because the browser run replaces the report for `WorkflowApiTest`. Browser failures include the captured process output or its log location.

Start the application after exporting the local provider configuration:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=workflow
```

The application binds to loopback by default. Missing credentials/configuration fail startup rather than fall back to fake rates. Four application tables are initialized directly from the checksummed `db/shipping/V001__shipping_schema.sql` release baseline; a separate schema-history table is infrastructure metadata. Do not reuse an earlier demo database as the workflow database. Do not edit an already-applied migration.

Application endpoint examples are in [the frontend handoff](../design/application-api.md). Provider details remain in the supplied integration reference. Automated backend and browser tests use local stubs and synthetic accounts; no credentials are needed for those tests. Never run uncontrolled real shipment-creation retries. Creation can be chargeable even in the provided sandbox.

## Application logs

Spring Boot/Logback writes to the console and `logs/shipping.log` by default. No shell redirection is required. Archives use `shipping.log.YYYY-MM-DD.N.gz`, rolling daily or at 10 MB. Retention is 14 days with a 1 GB archive cap; cleanup also runs at startup. The active log can add up to one file beyond the archive cap. Override the active file with `SHIPPING_LOG_FILE`; ensure its directory is writable. Relative paths resolve from the working directory (the launcher uses the project root). Timestamps, levels and request IDs are included. Custom log directories outside `logs/` must also stay out of version control.

## Consolidated first-release database

The project has not been released. Transitional development migrations have been removed; the baseline already contains final schema types, indexes and the retryable booking state. When moving from an earlier development schema, stop the application and remove the disposable database file configured by `SHIPPING_DATABASE_PATH`, including matching `-wal` / `-shm` files. Then use the normal launcher; no path change is needed. No automatic upgrade or deletion is performed. Repeated starts on the same baseline retain its data and verify the schema checksum. After release, use new migrations for subsequent schema changes.
