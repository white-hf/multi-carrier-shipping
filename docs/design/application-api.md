# Application API — frontend handoff

Status: Frontend integrated, backend implemented, and verified via automated API and Playwright E2E browser tests against a controlled local provider stub. The supplied frontend is integrated with the contracts below. All paths are relative to the backend origin. Use JSON; no application login UI or provider credentials are sent by the browser. The browser obtains a 2-hour HMAC-signed Guest Token bound to its client fingerprint (`POST /api/auth/guest`) and sends `Authorization: Bearer <token>` and `X-Client-Fingerprint: <fingerprint>` on subsequent API calls.

## Guest Session Token (2-hour TTL, Fingerprint-Bound)

`POST /api/auth/guest`

```json
{
  "clientId": "client-...",
  "fingerprint": "fp-..."
}
```

`200` returns a 2-hour (`7200s`) HMAC-SHA256 signed token bound to the client's fingerprint and sets `SameSite=Strict; HttpOnly` cookies (`shipping_guest_token`, `shipping_guest_fp`) for top-level label navigation:

```json
{
  "token": "<base64url-payload>.<base64url-hmac>",
  "userId": "guest-...",
  "mode": "GUEST",
  "expiresAt": "2026-10-23T17:20:00Z",
  "ttlSeconds": 7200
}
```

All `/api/quotes` and `/api/bookings/**` requests require a valid token and matching `X-Client-Fingerprint` (returning `401 UNAUTHORIZED` when missing, expired, or fingerprint-mismatched). Downstream controllers and services resolve the verified `userId` uniformly through `UserContext`.

## Rates

`POST /api/quotes` with required `Idempotency-Key: <one-token-per-distinct-shipment-input>`:

```json
{
  "sender": {"contactName":"Test Sender","addressLine1":"100 Test Street","city":"Toronto","province":"ON","postalCode":"M5V 3L9","countryCode":"CA","phone":"4165550100"},
  "recipient": {"contactName":"Test Recipient","addressLine1":"200 Test Street","city":"Halifax","province":"NS","postalCode":"B3J 3K1","countryCode":"CA","phone":"9025550100"},
  "parcel": {"length":12,"width":10,"height":8,"dimensionUnit":"IN","weight":5,"weightUnit":"LB","packageType":"PACKAGE"}
}
```

Optional address fields: `companyName`, `addressLine2`, `email`, `residential` (false default). All 13 Canadian province/territory codes are supported. Optional `shippingDate` is `yyyy-MM-dd`; when omitted, the backend initializes it in the configured business timezone. Dimensions and weight must be positive. Alternative units: CM and KG.

Initial completion returns `201` (with `Location: /api/quotes/{requestId}`); same-key replay or retry after `RATE_FAILED` / `QUOTES_EXPIRED` returns `200`; an already in-flight execution returns `202` with `Retry-After: 2`. `GET /api/quotes/{requestId}` reads current status (`RATING`, `READY`, `NO_RATES`, `RATE_FAILED`, `QUOTES_EXPIRED`) without calling the provider. Reusing a key with different shipment details returns `409 IDEMPOTENCY_CONFLICT`. When a rate operation already exists, error payloads include `"operation": {"id":"internal-request-id","statusUrl":"/api/quotes/internal-request-id"}`.

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

Values above are illustrative. Empty successful results have `state: NO_RATES` and `quotes: []`. Provider quote tokens never appear in frontend responses. Editing any shipping input clears the selected quote and generates a fresh rate `Idempotency-Key`. A 24-hour local browser draft (`localStorage`) restores form inputs and the active rate operation key across reloads, while the backend retains the authoritative `shipment_requests` business record. Ignore responses belonging to an older form revision. Do not invent transit estimates when null.

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

Status values: `PENDING`, `CONFIRMED`, `REJECTED`, `PENDING_CONFIRMATION`, `MANUAL_REVIEW`. A confirmed booking can have missing tracking and a pending label. Unknown creation must not be shown as rejection or retried with a new key. No automatic reference lookup is promised by the current provider contract.

When recovery cannot safely query or retry the provider, the existing booking becomes `MANUAL_REVIEW`:

```json
{
  "bookingId":"internal-booking-id","status":"MANUAL_REVIEW","shipmentId":null,
  "trackingNumber":null,"labelStatus":"PENDING","labelUrl":null,
  "errorCode":"AUTHORITATIVE_LOOKUP_UNSUPPORTED",
  "statusUrl":"/api/bookings/internal-booking-id"
}
```

Status reads and same-key manual-review replays return HTTP `200`; the replay has no automatic `Retry-After`. This is an unresolved booking, not creation success. Stop polling and hide creation/status retry controls. Preserve the booking reference and explain: **Do not submit it again. Contact support with the booking reference to verify the shipment before taking further action.** Refresh restores the same state; it may read current status once but does not restart polling. There are no manual resolution endpoints in this iteration.

Recovery uses provider/account capabilities. Reasons distinguish `AUTHORITATIVE_LOOKUP_UNSUPPORTED`, `AUTHORITATIVE_LOOKUP_UNVERIFIED`, and `AUTOMATIC_RECOVERY_UNAVAILABLE`; existing simulator inquiry exhaustion remains `INQUIRY_EXHAUSTED`. Capability metadata alone never enables a creation retry.

Different keys for the same request return `409` with `code: SHIPMENT_ALREADY_SUBMITTED` and the existing `booking` projection. Reusing a key for another quote returns `409 IDEMPOTENCY_CONFLICT`. An expired quote returns `409 QUOTE_EXPIRED`: retrieve rates again and ask the user to confirm.

## Label: one automatic attempt, then manual retry

`GET /api/bookings/{bookingId}/label` retrieves only the existing confirmed shipment's label:

- `200 application/pdf`: open or download the returned document.
- `303`: follow the authorized label location when opening directly. A fetch request accepting `application/json` receives `200 { "labelUrl": "..." }` instead, avoiding a cross-origin fetch of the document. The backend validates the destination host.
- `202`: label not ready; keep shipment creation confirmed and show a manual Retry Label action.

When confirmation has a pending label, the frontend may make **one automatic retrieval attempt**. It must not continuously poll this endpoint. No background label task, scheduler or queue is required. Recreating a shipment to obtain a label is forbidden. An unsupported label format or read failure is a label error, not failed shipment creation.

## Label actions (I5)

No new API is required. The confirmation screen uses the existing label endpoint (retaining the 2-hour fingerprint-bound Guest Token headers) and keeps shipment confirmation separate from label readiness.

- PDF response: retain a `File` and object URL in browser memory for viewing, downloading and capability-detected native file sharing. Clear/revoke these on replacement or a new shipment; never persist PDF bytes or object URLs in browser storage.
- Provider URL response: View opens the validated location. Saving/printing takes place in that viewer; do not depend on cross-origin JavaScript access or pretend a URL response is a PDF attachment.
- Print opens the PDF viewer and explains its print control or Ctrl/Cmd+P. It does not assert that a physical document was printed.
- Email opens a `mailto:` draft without a recipient or attachment. The user attaches the downloaded PDF and chooses whether to send. No backend email delivery is added.
- Share sends the file through the Web Share API when supported. User cancellation performs no fallback. Unsupported/failed sharing falls back to download or provider-viewer guidance; no public hosting or blob link sharing is introduced.
- Labels are retrieved automatically at most once for the active operation. After refresh, explicitly reload the label because browser-memory files are gone. Retry/reload never creates another shipment. Demo mode supplies no real label and disables all label actions.

## Errors and request correlation

```json
{"code":"INVALID_REQUEST","message":"INVALID REQUEST","requestId":"correlation-id","fieldErrors":[{"field":"parcel.weight","message":"must be greater than 0"}]}
```

Use `fieldErrors` for inline validation; preserve entered values. Read operations can return `502/503/504` for provider failure/capacity/deadline. A failed HTTP creation response does not prove no shipment was created: retain the key. All API responses receive `X-Request-Id` for diagnostics. Cross-origin browser writes are rejected; serve frontend assets from this backend origin.
