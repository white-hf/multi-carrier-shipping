# Application API — frontend handoff

Status: Frontend integrated, backend implemented, and verified via automated API and Playwright E2E browser tests against a controlled local provider stub. The supplied frontend is integrated with the contracts below. All paths are relative to the backend origin. Use JSON; no application login or provider credentials are sent by the browser. The backend resolves the fixed demo user.

## Rates

`POST /api/quotes`

```json
{
  "sender": {"contactName":"Test Sender","addressLine1":"100 Test Street","city":"Toronto","province":"ON","postalCode":"M5V 3L9","countryCode":"CA","phone":"4165550100"},
  "recipient": {"contactName":"Test Recipient","addressLine1":"200 Test Street","city":"Halifax","province":"NS","postalCode":"B3J 3K1","countryCode":"CA","phone":"9025550100"},
  "parcel": {"length":12,"width":10,"height":8,"dimensionUnit":"IN","weight":5,"weightUnit":"LB","packageType":"PACKAGE"}
}
```

Optional address fields: `companyName`, `addressLine2`, `email`, `residential` (false default). All 13 Canadian province/territory codes are supported. Optional `shippingDate` is `yyyy-MM-dd`; when omitted, the backend initializes it in the configured business timezone. Dimensions and weight must be positive. Alternative units: CM and KG.

`201` returns:

```json
{
  "requestId":"internal-request-id",
  "state":"READY",
  "shippingDate":"2026-10-23",
  "quotes":[{
    "quoteId":"internal-quote-id","carrierId":"14","carrierName":"Carrier",
    "serviceId":"1","serviceName":"Ground",
    "total":{"amount":"27.35","currency":"CAD"},
    "transitDays":2,"expiresAt":"2026-10-23T15:13:00Z"
  }]
}
```

Values above are illustrative. Empty successful results have `state: NO_RATES` and `quotes: []`. Provider quote tokens never appear in frontend responses. Editing any shipping input clears the selected quote. Ignore responses belonging to an older form revision. Do not invent transit estimates when null.

## Create and read booking

`POST /api/bookings` with required `Idempotency-Key: <one-random-token-per-confirmed-action>`:

```json
{"quoteId":"internal-quote-id"}
```

Keep the same key and quote ID after any uncertain response or browser retry. The backend sends quote-only creation to the provider. Initial success is `201`, completed replay is `200`, and pending/uncertain outcome is `202` with `Retry-After` and `Location` headers. Status is available through `GET /api/bookings/{bookingId}`.

```json
{
  "bookingId":"internal-booking-id","status":"CONFIRMED","shipmentId":"provider-shipment-id",
  "trackingNumber":null,"labelStatus":"PENDING",
  "labelUrl":"/api/bookings/internal-booking-id/label",
  "errorCode":null,"statusUrl":"/api/bookings/internal-booking-id"
}
```

Status values: `PENDING`, `CONFIRMED`, `REJECTED`, `PENDING_CONFIRMATION`. A confirmed booking can have missing tracking and a pending label. Unknown creation must not be shown as rejection or retried with a new key. No automatic reference lookup is promised by the current provider contract.

Different keys for the same request return `409` with `code: SHIPMENT_ALREADY_SUBMITTED` and the existing `booking` projection. Reusing a key for another quote returns `409 IDEMPOTENCY_CONFLICT`. An expired quote returns `409 QUOTE_EXPIRED`: retrieve rates again and ask the user to confirm.

## Label: one automatic attempt, then manual retry

`GET /api/bookings/{bookingId}/label` retrieves only the existing confirmed shipment's label:

- `200 application/pdf`: open or download the returned document.
- `303`: follow the authorized label location when opening directly. A fetch request accepting `application/json` receives `200 { "labelUrl": "..." }` instead, avoiding a cross-origin fetch of the document. The backend validates the destination host.
- `202`: label not ready; keep shipment creation confirmed and show a manual Retry Label action.

When confirmation has a pending label, the frontend may make **one automatic retrieval attempt**. It must not continuously poll this endpoint. No background label task, scheduler or queue is required. Recreating a shipment to obtain a label is forbidden. An unsupported label format or read failure is a label error, not failed shipment creation.

## Errors and request correlation

```json
{"code":"INVALID_REQUEST","message":"INVALID REQUEST","requestId":"correlation-id","fieldErrors":[{"field":"parcel.weight","message":"must be greater than 0"}]}
```

Use `fieldErrors` for inline validation; preserve entered values. Read operations can return `502/503/504` for provider failure/capacity/deadline. A failed HTTP creation response does not prove no shipment was created: retain the key. All API responses receive `X-Request-Id` for diagnostics. Cross-origin browser writes are rejected; serve frontend assets from this backend origin.
