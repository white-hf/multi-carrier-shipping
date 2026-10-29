# Real sandbox validation cases

[Quote case catalog](quote-validation-cases.json) · [Execution and acceptance plan](../../docs/runbooks/sandbox-validation.md)

These are declarative test definitions, not an executable test runner or evidence of live acceptance. Every case starts from the baseline and applies its own dotted-field changes; do not accumulate changes between cases. Omitted shippingDate is intentional: the application supplies the current business date.

- `browser`: exercise normal UI validation. Assert zero quote POSTs and zero downstream rates calls; guest authentication traffic is allowed.
- `browser-and-backend-api`: run both normal browser submission and direct API variants; HTML allows these formats but backend validation must reject them before a provider call.
- `backend-api`: use valid guest authentication and call the public local API directly. This bypasses only browser validation, never backend safety checks.
- `live-browser`: keep local validation enabled, demo mode off and the real provider configured. No provider-route interception or mock responses. Confirm a correlated downstream call and its actual status.

The browser field mapping remains the application's normal mapping: sender/recipient -> from/to fields, contactName -> attention, addressLine1 -> streetAddress, province -> state. Use the unit controls for parcel units. Do not call the private provider adapter directly to make an invalid fixture reach the provider.

Start with a validated positive fixture. The address-mismatch inputs are unconfirmed negative candidates. Provider normalization/acceptance or a no-rates response means the fixture did not trigger the target rule; it is not evidence that validation rejection passed.

The catalog is read-only: never select a rate and create a shipment automatically. Chargeable write acceptance is a separate, bounded run described in the plan. Configuration and evidence containing secrets remain local and ignored. Run only after the provider-layer refactor is integrated and its exact commit is recorded.
