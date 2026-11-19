# Start and try the application

Requires Java 17+. Run commands from the repository root. No frontend build is needed.

## I10 upgrade: an old database must be explicitly rebuilt

**I10 changes V001 to the stateless-quote baseline. An existing database can fail startup with `Applied migration checksum changed: V001__shipping_schema.sql`. This is a schema mismatch, not an authentication/controller dependency problem.** Historical-data migration is out of scope by product decision.

Stop the application first. To delete the configured old database **and all its bookings**, remove its WAL/SHM files and start with the new schema:

```sh
./scripts/start-workflow.sh --reset-database
```

To delete without starting, use `--reset-database-only`. The reset uses the effective `SHIPPING_DATABASE_PATH` after loading `.env`; when `lsof` is available it refuses files held by a running process. Normal startup never silently resets a database. Do not change `schema_migrations` checksums to bypass this failure.

To preserve the old file and start separately instead:

```sh
SHIPPING_DATABASE_PATH=./data/stateless.db ./scripts/start-workflow.sh
```

An explicitly exported database-path override wins over `.env`. Other `.env` precedence remains unchanged. For direct `java -jar` startup, select the same intended database with `SHIPPING_DATABASE_PATH` or `--shipping.database-path`.

## Local configuration

The launcher reads `.env` from the **project root**, beside `.env.example` and `pom.xml`.

On a new machine, copy the [configuration template](../../.env.example) only if `.env` does not already exist:

```sh
cp .env.example .env
```

Edit `.env` and supply the provider credentials and separate application login settings:

```sh
SHIPPING_PROVIDER_BASE_URL=''
SHIPPING_PROVIDER_USERNAME=''
SHIPPING_PROVIDER_PASSWORD=''
SHIPPING_AUTH_USERNAME=''
SHIPPING_AUTH_PASSWORD='' # unique password, at least 12 characters
SHIPPING_AUTH_TOKEN_SECRET='' # generate with: openssl rand -hex 32
```

See [account access](account-access.md) for password hashes, multiple accounts, quotas and HTTPS/proxy setup. Application login credentials are separate from provider credentials.

Use your provider's integration URL and credentials. Keep `.env` local; it is ignored by Git. The template also includes optional ports, database path and timeout settings. Values defined in `.env` take precedence except an explicit `SHIPPING_DATABASE_PATH` override. For another local file, set `SHIPPING_ENV_FILE` to its absolute path.

Verify the settings without calling the provider:

```sh
./scripts/start-workflow.sh --check-config
```

## Start

```sh
./scripts/start-workflow.sh
```

The script loads the local configuration, builds the application and starts it.

For an already-built, verified artifact, use `./scripts/start-workflow.sh --run-jar /absolute/path/application.jar`.
This loads the same local configuration and starts that JAR without rebuilding or resetting the database.
For a long-running development service, copy the verified artifact to a dedicated release directory first,
so later test/package runs do not replace the JAR used by the running process. Startup remains foreground;
use the machine's process manager or a detached launcher with a retained log when publishing locally.

Open <http://127.0.0.1:8080/> after startup. Sign in using the application account configured above. The workspace uses the backend API; there is no local demo mode.

## Try the workflow

1. Enter shipment details and click **Compare shipping rates**. The provider can take tens of seconds.
2. Select an offer and review the service and price.
3. Confirm one shipment; this calls the sandbox and may charge the configured account.
4. Use **View label**, **Download PDF**, **Print label**, **Email label** or **Share label** once ready. Use **Retry label** while pending.
   Email opens your mail app; attach the downloaded PDF yourself. Print opens the PDF viewer. Sharing uses the browser/OS when supported, otherwise downloads the PDF or opens the provider viewer.
5. Refresh the same tab to restore the existing booking. Use **Retry label** / **Reload label** to retrieve its PDF again; no new shipment is created.

If creation shows **Pending confirmation**, use **Check Status** for the existing operation rather than submitting it again. After recovery detects unsupported lookup, **Manual review required** appears: polling stops and retry controls disappear. Keep the booking reference and contact support to verify the provider result. Refreshing does not create a new shipment.

## Logs

Application logs are written automatically to `logs/shipping.log` and the console. Files roll daily or at 10 MB, with compressed archives retained for up to 14 days and a 1 GB archive limit. Set `SHIPPING_LOG_FILE` in `.env` to change the path. The `logs/` directory is ignored by Git.

## Check and stop

Optional health check in another terminal:

```sh
./scripts/check-workflow.sh
```

Press **Ctrl+C** in the startup terminal to stop. Run the same startup command to restart; local SQLite data is retained.

For configuration details and automated tests, see [workflow setup](workflow-setup.md). Coverage and limitations are in the [acceptance report](../summaries/acceptance-test-report.md).
