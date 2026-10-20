# Separate simulator infrastructure from business code

Approved scope: remove simulator fields from the public request and domain intent; isolate simulated protocol adapters, fixed ownership, carrier database/wiring, and fault controls under demo. Make business features independent of that profile. Replace embedded market assumptions with configured country/postal and currency rules. Keep the aggregate and SQL concurrency controls.

The project is under development: support only the current business intent model, reject unknown request fields, and use fresh local data after model changes. Country-less callers resolve countries through configured policy. No legacy compatibility code or migration flags are required.

Validate core operation without demo beans, principal ownership, strict request parsing, market/currency configuration, server-side failure injection, durable idempotency. Existing carrier-admission changes remain part of the working tree.
