# Business code and runtime infrastructure

The application contains business workflows and explicit simulator implementations. The simulator is selected by a profile rather than encoded into request/domain objects.

| Concern | Business/core responsibility | Runtime implementation or data |
| --- | --- | --- |
| Booking intent | Order, carrier, service, parcel, countries | No client-controlled fault mode |
| Ownership | `BookingOwnerProvider` resolves a trusted owner | Authenticated principal normally; fixed merchant in demo only |
| Carrier operations | `CarrierRatePort`, `CarrierBookingPort`, `CarrierInquiryPort` | Simulated contracts under `demo`; real adapters must implement real protocols |
| Carrier availability | Admission validation in application code | `shipping.booking.enabled-carriers` |
| Market | `ShippingMarketPolicy` validates country/postal rules and currency | `shipping.market` configuration |
| Fault injection | None in business objects or hashes | `demo.booking-carrier.mode` and `demo.quote-carriers` |
| Recovery | Unique claims, aggregate transitions, leases and inquiry | Same business code with either infrastructure |

Core imports no demo package. Booking, reconciliation and market configuration load unconditionally. Quote activation and reconciliation scheduling retain their own settings. Base configuration contains no mock URLs or carrier fixtures. The demo profile supplies market data and simulator beans; outside it, required configuration and ports must be provided. Missing dependencies fail startup rather than silently selecting a fake carrier.

The default owner provider relies on an established principal; it is not an authentication implementation. Non-demo integration tests provide a test port and authenticated principals, without loading any demo bean, and verify cross-owner access is rejected.

## Development data

Only the current intent format is supported. Fingerprints include resolved country codes and contain no simulation field. Unknown request fields are rejected. There are no legacy decoders, migration flags, or compatibility fixtures. Use fresh local demo database files when changing the intent model during development.
