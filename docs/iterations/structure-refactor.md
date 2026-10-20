# Unify quote and booking structure

Approved scope: organize both features with API, application, domain, ports, infrastructure, and configuration packages. Extract neutral parcel input/domain types, common API handling, and HTTP construction. Keep carrier capabilities separate and resource/retry policies isolated. Move quote metrics behind an observer. Group simulations under demo.

Acceptance: preserve HTTP contracts, configuration keys, SQLite schema, stored booking fingerprints, and existing recovery behavior. Run all existing tests plus fingerprint-compatibility and quote-metrics-failure regressions. Validate a packaged restart workflow.

No production carrier integration, database migration, endpoint redesign, Maven multi-module split, or new business capability is part of this refactor.
