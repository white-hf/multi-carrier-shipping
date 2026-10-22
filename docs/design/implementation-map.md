# Implementation map

Code entry points for the independent Java 17 demo: rate comparison, shipment booking, reconciliation and observability.

| Responsibility | Entry point |
| --- | --- |
| Quote HTTP endpoint | [QuoteController](../../src/main/java/com/enterprise/shipping/quote/api/QuoteController.java) |
| Fan-out, deadlines, capacity | [HighFanOutAggregationEngine](../../src/main/java/com/enterprise/shipping/quote/application/HighFanOutAggregationEngine.java) |
| Rate capability | [CarrierRatePort](../../src/main/java/com/enterprise/shipping/quote/port/CarrierRatePort.java) |
| FedEx / UPS translation | [Rate adapters](../../src/main/java/com/enterprise/shipping/demo/quote/) |
| Quote application metrics | [MicrometerQuoteObserver](../../src/main/java/com/enterprise/shipping/quote/infrastructure/monitoring/MicrometerQuoteObserver.java) |
| Booking lifecycle rules | [ShipmentBooking](../../src/main/java/com/enterprise/shipping/booking/domain/ShipmentBooking.java) |
| Booking orchestration | [BookingService](../../src/main/java/com/enterprise/shipping/booking/application/BookingService.java) |
| Inquiry-only recovery | [ReconciliationWorker](../../src/main/java/com/enterprise/shipping/reconciliation/application/ReconciliationWorker.java) |
| Recovery business boundary | [BookingRecoveryService](../../src/main/java/com/enterprise/shipping/booking/application/BookingRecoveryService.java) |
| Recovery task storage | [JdbcReconciliationTaskStore](../../src/main/java/com/enterprise/shipping/reconciliation/infrastructure/persistence/JdbcReconciliationTaskStore.java) |
| Intent fingerprints | [BookingRequestHasher](../../src/main/java/com/enterprise/shipping/booking/application/BookingRequestHasher.java) |
| Module assembly | [ReconciliationConfiguration](../../src/main/java/com/enterprise/shipping/reconciliation/config/ReconciliationConfiguration.java) |
| Cross-module wiring | [RecoveryRuntimeConfiguration](../../src/main/java/com/enterprise/shipping/runtime/RecoveryRuntimeConfiguration.java) |
| Shared SQLite setup | [SqliteDataSources](../../src/main/java/com/enterprise/shipping/shared/infrastructure/persistence/SqliteDataSources.java) |
| Atomic booking storage | [JdbcBookingStore](../../src/main/java/com/enterprise/shipping/booking/infrastructure/persistence/JdbcBookingStore.java) |
| Shared parcel intent | [ShipmentParcel](../../src/main/java/com/enterprise/shipping/shared/domain/ShipmentParcel.java) |
| HTTP construction / pool isolation | [CarrierHttpClientFactory](../../src/main/java/com/enterprise/shipping/shared/infrastructure/http/CarrierHttpClientFactory.java) |
| Transport monitoring | [CarrierMonitoringAspect](../../src/main/java/com/enterprise/shipping/shared/infrastructure/monitoring/CarrierMonitoringAspect.java) |

[Package boundaries](code-organization.md) · [Rate design](rate-comparison.md) · [Booking design](booking-sqlite.md) · [Latest validation](../summaries/reconciliation-separation.md)
