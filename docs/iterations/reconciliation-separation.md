# Reconciliation table and module separation

Approved scope: keep one process, split task scheduling/leases/retries from booking state. Do not add task writes to normal bookings. Persist stable carrier/account/reference identity in recovery tasks. Keep carrier inquiry read-only and booking transitions behind a booking-owned port. No backward compatibility or automatic database migration.

Acceptance: normal success/rejection create no tasks; timeout and stale claims are rediscovered; concurrent discovery and worker claims deduplicate; stale task writes are fenced; business writes occur only on transitions; durable results survive lost acknowledgements/restart; indexes support ordered discovery and due work. Keep paused tasks for review and document future extraction/retention limitations.
