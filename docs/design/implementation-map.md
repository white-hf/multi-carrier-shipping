# Implementation map

Current feature-based Java 17 demo. This describes repository code, not ShipTime internals.

| Responsibility | Entry point |
| --- | --- |
| Quote HTTP endpoint | [QuoteController](../../src/main/java/com/enterprise/shipping/quote/api/QuoteController.java) |
| Fan-out, deadlines, capacity | [HighFanOutAggregationEngine](../../src/main/java/com/enterprise/shipping/quote/application/HighFanOutAggregationEngine.java) |
| Rate capability | [CarrierRatePort](../../src/main/java/com/enterprise/shipping/quote/port/CarrierRatePort.java) |
| FedEx / UPS translation | [Rate adapters](../../src/main/java/com/enterprise/shipping/demo/quote/) |
| Quote application metrics | [MicrometerQuoteObserver](../../src/main/java/com/enterprise/shipping/quote/infrastructure/monitoring/MicrometerQuoteObserver.java) |
| Booking lifecycle rules | [ShipmentBooking](../../src/main/java/com/enterprise/shipping/booking/domain/ShipmentBooking.java) |
| Booking orchestration | [BookingService](../../src/main/java/com/enterprise/shipping/booking/application/BookingService.java) |
| Inquiry-only recovery | [BookingReconciliationWorker](../../src/main/java/com/enterprise/shipping/booking/application/BookingReconciliationWorker.java) |
| Atomic booking storage | [JdbcBookingStore](../../src/main/java/com/enterprise/shipping/booking/infrastructure/persistence/JdbcBookingStore.java) |
| Shared parcel intent | [ShipmentParcel](../../src/main/java/com/enterprise/shipping/shared/domain/ShipmentParcel.java) |
| HTTP construction / pool isolation | [CarrierHttpClientFactory](../../src/main/java/com/enterprise/shipping/shared/infrastructure/http/CarrierHttpClientFactory.java) |
| Transport monitoring | [CarrierMonitoringAspect](../../src/main/java/com/enterprise/shipping/shared/infrastructure/monitoring/CarrierMonitoringAspect.java) |

[Package boundaries](code-organization.md) · [Rate design](rate-comparison.md) · [Booking design](booking-sqlite.md) · [Latest validation](../summaries/runtime-boundaries.md)
