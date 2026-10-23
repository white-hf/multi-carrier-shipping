# Start and try the application

Requires Java 17+. Run commands from the repository root. No frontend build is needed.

## Local configuration

The launcher reads `.env` from the **project root**, beside `.env.example` and `pom.xml`.

On a new machine, copy the [configuration template](../../.env.example) only if `.env` does not already exist:

```sh
cp .env.example .env
```

Edit `.env` and supply these three required values:

```sh
SHIPPING_PROVIDER_BASE_URL=''
SHIPPING_PROVIDER_USERNAME=''
SHIPPING_PROVIDER_PASSWORD=''
```

Use your provider's integration URL and credentials. Keep `.env` local; it is ignored by Git. The template also includes optional ports, database path and timeout settings. Existing exported variables work too; values defined in `.env` take precedence. For another local file, set `SHIPPING_ENV_FILE` to its absolute path.

Verify the settings without calling the provider:

```sh
./scripts/start-workflow.sh --check-config
```

## Start

```sh
./scripts/start-workflow.sh
```

The script loads the local configuration, builds the application and starts it.

Open <http://127.0.0.1:8080/> after startup. Ensure **Demo mode is off** and the indicator says **Backend API**.

## Try the workflow

1. Enter shipment details and click **Compare shipping rates**. The provider can take tens of seconds.
2. Select an offer and review the service and price.
3. Confirm one shipment; this calls the sandbox and may charge the configured account.
4. Open the label, or use **Retry label** if it is pending.
5. Refresh the same tab to restore the existing booking.

If creation shows **Pending confirmation**, use **Check Status** for the existing operation rather than submitting it again.

## Check and stop

Optional health check in another terminal:

```sh
./scripts/check-workflow.sh
```

Press **Ctrl+C** in the startup terminal to stop. Run the same startup command to restart; local SQLite data is retained.

For configuration details and automated tests, see [workflow setup](workflow-setup.md). Coverage and limitations are in the [acceptance report](../summaries/acceptance-test-report.md).
