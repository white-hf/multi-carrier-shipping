# Runtime boundary validation

Java 17 clean build and 76 tests pass. The suite retains existing concurrency, lease fencing, aggregate, HTTP timeout, carrier admission, and metrics checks, while adding core wiring without the demo profile, authenticated owner isolation, strict rejection of simulator fields, configured postal/currency behavior.

The non-demo test supplies a test carrier port rather than loading simulator beans. It confirms unauthenticated requests return 401 and one principal cannot access another principal's booking. A separate test changes the comparison currency to USD. Fault modes are selected in simulator/test infrastructure, never request commands.

Development uses only the current intent format. Legacy decoders, flags, and fixtures have been removed. Idempotent replay compares the current normalized request hash with its stored hash directly. Use fresh local demo database files after intent changes.

Import checks found no core-to-demo or shared-to-feature dependencies. Business request/domain objects have no simulator fields. Mock defaults and carrier fixtures are confined to application-demo.yml and demo packages. The SQLite application schema and response envelopes remain unchanged; new request hashes intentionally reflect the new business-only intent with resolved countries.

A new packaged cross-version smoke run was not executed because permission for that command was declined. The evidence above is from the full automated suite, not a claim of a newly completed packaged upgrade check. Prior historical smoke results remain in their original summaries.

[Design](../design/runtime-boundaries.md) · [Booking runbook](../runbooks/booking-demo.md)

Measurement tests verify inch-to-centimetre carrier payloads, explicit required units, rejection of unknown units and physical limits after conversion, and identical booking replay for equivalent imperial/metric intent. Conversion uses exact decimal factors without rounding.
