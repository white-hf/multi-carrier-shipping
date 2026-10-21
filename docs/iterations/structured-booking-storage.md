# Structured booking persistence

Approved scope: replace request/response JSON duplication with explicit booking intent and outcome columns. Reconstruct API responses from persisted business state. Preserve normalized request hashing, unique claim ownership, version/token fencing, and inquiry-only recovery. Improve the SQLite recovery index while retaining useful uniqueness constraints.

Keep exact decimal measurements through SQLite persistence. Verify intent/response round trips, concurrency regressions, recovery ordering and filtering, and actual JDBC query plans. This development project uses fresh database files after schema changes; no migration or backward compatibility is required.
