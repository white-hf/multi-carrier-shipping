# Booking domain model iteration

Approved scope: introduce a shipment-booking aggregate that owns lifecycle rules, receipt validation, and immutable identity. Keep quotes lightweight. Application services orchestrate carriers and persistence; SQL retains uniqueness and version/token fencing.

Move the database snapshot to the storage port; do not put serialized JSON, scheduling, or leases into the aggregate. Preserve API and storage formats, idempotency fingerprints, metrics, and inquiry-only recovery. Add plain domain tests and retain the existing integration tests. No domain-event infrastructure or new business capability is required.
