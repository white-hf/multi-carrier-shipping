# UUIDv7 persisted identifiers

Booking and reconciliation task primary keys now use `TimeOrderedIds.next()`, backed by `com.github.f4b6a3:uuid-creator:6.1.1`. Library usage is isolated in one shared helper; no UUID bit manipulation or custom random generator is introduced. Canonical UUID text storage and API formats stay unchanged.

UUIDv7 improves insertion locality through its time-leading layout. It does not eliminate page splits, compact text storage, provide global chronological ordering across processes, or implement shard routing. Random lease tokens, quote request IDs, and simulated carrier-generated receipt IDs retain their existing independent roles.

Validation: clean package with 90 passing tests, including persisted booking/task UUID version checks, canonical text ordering in one generator and concurrent uniqueness across 8,000 generated IDs. No database migration is needed for this identifier-generation change.

Primary reference: [UUID Creator 6.1.1 source](https://github.com/f4b6a3/uuid-creator/tree/uuid-creator-6.1.1).
