# Structured booking persistence validation

Java 17 clean package build: all 79 tests pass, with no failures or skipped tests. The original 76 remain, plus three file-backed persistence/query-plan tests.

The booking table now stores business intent and outcome fields directly, with no request_json or response_json columns. Decimal measurements round-trip exactly through canonical text and BigDecimal, including values beyond double precision. A test closes/reopens the SQLite data source, verifies the original intent/hash, and reconstructs the same confirmed response.

The actual JDBC recovery query uses idx_booking_inquiry and requires no temporary sorting B-tree. Tests also verify due-time order, booking-ID tie breaking, batch limits, exclusion of live leases and closed states, owner/key index lookup, booking-ID lookup, and primary-key access for fenced updates. The two range predicates still require a lease-expiry filter; query-plan validation is not a production load benchmark.

Existing HTTP tests verify metric/imperial replay equivalence against structured measurement columns. Existing workflow tests retain concurrent claim, stale-writer fencing, restart recovery, rejection replay, and persistence-failure coverage. No packaged smoke check was added in this iteration.

[Persistence design and indexes](../design/booking-sqlite.md#business-columns-and-indexes) · [Fresh development data](../runbooks/booking-demo.md#errors-and-development-data)
