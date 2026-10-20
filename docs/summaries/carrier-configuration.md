# Configuration-backed booking carriers

Booking input checks presence and identifier length without a fixed carrier regex. `shipping.booking.enabled-carriers` supplies an immutable, normalized startup policy. New operations with an unconfigured carrier return 400 / UNSUPPORTED_CARRIER before claim/submission; existing keys are looked up first so replay and inquiry recovery remain available after disabling a carrier.

Validation: all 69 tests pass, including configuration-only onboarding of an additional simulated carrier, normalized IDs, unsupported-carrier rejection without side effects, disabled-carrier replay and recovery, and startup rejection of blank/overlength configuration. Existing domain, concurrency, fingerprint, and HTTP tests remain intact. No database or persisted-intent changes are required.

[Carrier configuration](../runbooks/booking-demo.md#carrier-configuration)
