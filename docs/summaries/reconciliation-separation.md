# Reconciliation separation validation

Implemented an independent reconciliation module, neutral discovery/observation contracts and `booking_reconciliation_tasks`. The task table persists carrier/account/reference lookup identity, leases, attempts, backoff state and structured observations. Booking owns aggregate transitions; normal bookings never write the task table. Runtime currently shares one SQLite datasource without cross-table transactions or joins.

Validation: 88 tests passed with no failures/errors, including real HTTP timeout recovery, database reopen, concurrent insert/worker races, failed discovery registration, expired task fencing, lost acknowledgement plus evidence redelivery after reopen, failed task deletion, booking version stability across unresolved retries, keyset wraparound, account-reference validation and actual JDBC query plans. Existing quote, domain and API regression tests remain included.

Use fresh development database files; no migration is supplied. SQLite cluster deployment, remote recovery transport, operator resolution and retention/archival are outside this change.
