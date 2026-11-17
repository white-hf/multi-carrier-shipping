# Application API — Frontend & Backend Protocol Contract

Status: Frontend integrated, backend implemented, and verified via automated API and Playwright E2E browser tests against a controlled local provider stub. All paths are relative to the backend origin (`http://127.0.0.1:8080`).

---

## 1. Protocol & Response Envelope Convention

This API follows a **RESTful HTTP-native protocol convention** (aligned with Stripe / GitHub / RFC 9457 Problem Details) rather than wrapping every response in a `200 OK { code, msg, data }` envelope:

1. **Success Responses (`200 OK`, `201 Created`, `202 Accepted`) — Unwrapped Resource Payload**
   - HTTP status codes convey the exact operation semantics (`201` newly created resource, `200` idempotent replay or read, `202` in-flight / pending confirmation).
   - The JSON response body is the **business resource object directly** (no outer `data` wrapper).
   - Diagnostic and routing metadata are carried in standard HTTP headers:
     - `X-Request-Id`: Time-ordered UUIDv7 correlation ID generated for every request.
     - `Location`: Canonical status URI of the created or conflicted resource (`/api/quotes/{requestId}` or `/api/bookings/{bookingId}`).
     - `Retry-After`: Recommended polling delay in seconds (sent on `202 Accepted`).

2. **Error Responses (`4xx` / `5xx`) — Unified Structured Error Envelope**
   - Every non-2xx response is serialized by `ShippingExceptionHandler` (or `ShippingAuthenticationFilter`) using a single, predictable JSON error schema:
     ```json
     {
       "code": "INVALID_REQUEST",
       "message": "INVALID REQUEST",
       "requestId": "01a17114-61e8-7e93-89cd-03648aac54cf",
       "fieldErrors": [
         {
           "field": "parcel.weight",
           "message": "must be greater than 0"
         }
       ],
       "operation": {
         "id": "01a17114-61ea-76bc-9adc-3df32a852375",
         "statusUrl": "/api/quotes/01a17114-61ea-76bc-9adc-3df32a852375"
       }
     }
     ```
   - `code`: Bounded, machine-readable uppercase error code (never raw stack traces or upstream provider bodies).
   - `message`: Human-readable summary derived safely from `code`.
   - `requestId`: Matches the `X-Request-Id` response header for log correlation.
   - `fieldErrors`: Array of `{ field, message }` objects for inline form highlighting (`[]` when not a field validation error).
   - `operation` *(optional)*: Included when an error relates to an already-persisted rate request (`IDEMPOTENCY_CONFLICT`, `REQUEST_DATE_EXPIRED`, or failed rate execution).

| HTTP Status | Category | Representative `code` Values |
| --- | --- | --- |
| `200 OK` | Read / Idempotent Replay | Returns resource JSON directly (or `application/pdf` bytes for ready labels) |
| `201 Created` | Initial Resource Creation | Returns newly created quote set or confirmed booking JSON + `Location` header |
| `202 Accepted` | Async / Pending Outcome | `RATING` in progress, `PENDING_CONFIRMATION` booking, or `PENDING` label + `Retry-After` header |
| `400 Bad Request` | Validation Failure | `INVALID_REQUEST`, `MISSING_IDEMPOTENCY_KEY`, `INVALID_IDEMPOTENCY_KEY`, `INVALID_SHIPPING_DATE` |
| `401 Unauthorized` | Auth / Token Failure | `UNAUTHORIZED` (missing, expired, or fingerprint-mismatched account token) |
| `429 Too Many Requests` | Login / API Quota | `AUTH_RATE_LIMITED`, `API_RATE_LIMITED` + `Retry-After` |
| `403 Forbidden` | Cross-Origin Write | `ORIGIN_NOT_ALLOWED` |
| `404 Not Found` | Missing / Foreign Owner | `RESOURCE_NOT_FOUND` |
| `409 Conflict` | State / Key Conflict | `IDEMPOTENCY_CONFLICT`, `SHIPMENT_ALREADY_SUBMITTED`, `QUOTE_EXPIRED`, `QUOTE_NOT_BOOKABLE`, `REQUEST_DATE_EXPIRED` |
| `422 Unprocessable` | Carrier Rejection | `CARRIER_REJECTED` (returns booking projection with `status: "REJECTED"`) |
| `502 / 503 / 504` | Upstream / Storage Fault | `PROVIDER_UNAVAILABLE`, `PROVIDER_INVALID_RESPONSE`, `PROVIDER_CAPACITY_EXCEEDED`, `PROVIDER_TIMEOUT`, `STORE_UNAVAILABLE` |

---

## 2. Account Session (2-hour TTL, Fingerprint-Bound)

`POST /api/auth/login` is the only credential-issuing endpoint. Guest issuance is removed.

```json
{
  "username": "configured-operator",
  "password": "<configured-password>",
  "fingerprint": "fp-client-device-123"
}
```

`200` returns `token`, `userId`, `username`, `displayName`, `mode: "AUTHENTICATED"`,
`expiresAt`, `ttlSeconds` (default 7200), and `supportedCarriers`. Login sets the existing
`shipping_guest_token` and `shipping_guest_fp` cookie names for compatible label navigation:
`SameSite=Strict; HttpOnly; Path=/api`, plus `Secure` when the container reports HTTPS.
The token signature uses a new account-specific domain, invalidating previously issued guest tokens.

All other `/api/**` requests require a valid account token and matching `X-Client-Fingerprint`.
For GET label navigation, cookies supply the token and fingerprint. Missing/expired/mismatched
credentials or removed account identities return `401 UNAUTHORIZED`. Incorrect login credentials
return `401 INVALID_CREDENTIALS` with the same message for nonexistent users and wrong passwords.

`POST /api/auth/logout` returns `204` and expires both cookies. The browser clears its session
and workspace and returns to sign-in. Tokens are stateless: a previously copied token/fingerprint
pair remains usable until expiry; logout does not revoke copies on other clients. Changing the
signing secret invalidates all existing tokens, and removing an account blocks its user ID.

Login and logout remain subject to same-origin write checks. Login has a global 5/sec sliding
limit, plus IP and IP/username-pair failure lockouts (5 failures in 5 minutes → 15 minutes).
Locked sources do not consume the shared login budget. Anonymous failures do not lock a username
globally, so the same account can sign in from another IP. Authenticated
requests have independent sliding quotas per user and peer IP: 10 quote POSTs, 5 booking POSTs,
and 20 label GETs/HEADs per minute. Same-key replays count too. `429 AUTH_RATE_LIMITED` or
`429 API_RATE_LIMITED` includes integer `Retry-After` seconds. Limits are per process, with a
bounded 4096-entry table that rejects new keys while full and reclaims expired entries.

The frontend requires explicit sign-in, never refreshes anonymously, and never automatically
replays a write on 401. Switching accounts clears drafts/operation caches; the same account can
re-authenticate after expiry and recover its active operation. All operations use the backend; local
demo mode and sample-data execution are removed. See [Account access deployment](../runbooks/account-access.md) for credentials and proxy setup.

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

Initial completion returns `201` (with `Location: /api/quotes/{requestId}`); same-key replay or retry after `RATE_FAILED` / `QUOTES_EXPIRED` returns `200`; an already in-flight execution or a retry claim that loses contention returns `202` with `Retry-After: 2` (`state: "RATING"` or response-only `state: "CONTENDED"` with the known `requestId` and `quotes: []`). When `CONTENDED` is returned, the client inspects current state via `GET /api/quotes/{requestId}` (`RATING`, `READY`, `NO_RATES`, `RATE_FAILED`, `QUOTES_EXPIRED`) without calling the provider or clearing already-rendered quotes. Reusing a key with different shipment details returns `409 IDEMPOTENCY_CONFLICT`. When a rate operation already exists, error payloads include `"operation": {"id":"internal-request-id","statusUrl":"/api/quotes/internal-request-id"}`.

Shipping-date eligibility uses the configured business timezone. Once the stored shipping date is in the past, matching-key POST replays and booking selection return `409 REQUEST_DATE_EXPIRED`. GET remains available for history: a previously READY request is projected as `QUOTES_EXPIRED` with no usable quotes, even if its provider quote TTL has not elapsed. A new shipping date requires a new request/key.

If a superseded rate execution fails after a newer version has claimed the request, its response reflects the latest persisted request instead of reporting the superseded provider error: `200` for a completed state or `202` while the newer execution is still running.

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

Keep the same key and quote ID after any uncertain response or browser retry. The backend sends quote-only creation to the provider. Initial success is `201`, completed replay is `200`, and pending/uncertain outcome or a lost retry claim (`status: "CONTENDED"`, response-only) is `202` with `Retry-After` and `Location` headers. When `CONTENDED` is returned, the client polls `GET /api/bookings/{bookingId}` to observe the authoritative persisted state (`PENDING`, `RETRYABLE`, `CONFIRMED`, `REJECTED`, `PENDING_CONFIRMATION`, `MANUAL_REVIEW`) without overwriting any already-confirmed result.

```json
{
  "bookingId":"internal-booking-id","status":"CONFIRMED","shipmentId":"provider-shipment-id",
  "trackingNumber":null,"labelStatus":"PENDING",
  "labelUrl":"/api/bookings/internal-booking-id/label",
  "errorCode":null,"statusUrl":"/api/bookings/internal-booking-id"
}
```

Status values: `PENDING`, `RETRYABLE`, `CONFIRMED`, `REJECTED`, `PENDING_CONFIRMATION`, `MANUAL_REVIEW`. A confirmed booking can have missing tracking and a pending label. Unknown creation must not be shown as rejection or retried with a new key. No automatic reference lookup is promised by the current provider contract.

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

## Creation refused before dispatch: explicit retry

A local create bulkhead rejection or open circuit has not sent a provider request. The booking is persisted as `RETRYABLE`, and creation returns **503** with `Retry-After: 2`, a `Location` status URL and the standard error envelope:

```json
{
  "code": "SHIPMENT_SUBMISSION_NOT_DISPATCHED",
  "message": "SHIPMENT SUBMISSION NOT DISPATCHED",
  "requestId": "correlation-id",
  "fieldErrors": [],
  "operation": { "id": "booking-id", "statusUrl": "/api/bookings/booking-id" }
}
```

`GET /api/bookings/{id}` returns `status: RETRYABLE` and the same error code. A user-triggered retry must resubmit **the same quote ID and Idempotency-Key** to `POST /api/bookings`; it reuses the existing booking. Concurrent retries compete for one version-fenced claim. Do not run a rapid automatic retry loop. Repeated local rejection remains `RETRYABLE` and creates no manual-review task.

The UI renders proven-unsent submissions as **Shipment not submitted**, retains the booking locator and original key/quote across reloads, and offers **Retry shipment**. Reload reads current status without automatically submitting; RETRYABLE ends polling. An explicit retry POST uses the original key and quote even when a booking ID is known.

The backend revalidates the quote before retrying. An expired/unavailable quote is not dispatched; obtain a new quote/request and ask the user to confirm it. Different keys for the original request remain conflicts. A dispatched timeout or lost response is still `PENDING_CONFIRMATION`, not `RETRYABLE`, and must never enable blind resubmission. Generic HTTP/network failures are not proof that creation was unsent.

The frontend handles this retryable state explicitly; uncertain dispatched outcomes continue to use status checks rather than a new-key submission.

## Label: one automatic attempt, then manual retry

`GET /api/bookings/{bookingId}/label` retrieves only the existing confirmed shipment's label:

- `200 application/pdf`: open or download the returned document.
- `303`: follow the authorized label location when opening directly. A fetch request accepting `application/json` receives `200 { "labelUrl": "..." }` instead, avoiding a cross-origin fetch of the document. The backend validates the destination host.
- `202`: label not ready; keep shipment creation confirmed and show a manual Retry Label action.

When confirmation has a pending label, the frontend may make **one automatic retrieval attempt**. It must not continuously poll this endpoint. No background label task, scheduler or queue is required. Recreating a shipment to obtain a label is forbidden. An unsupported label format or read failure is a label error, not failed shipment creation.

## Label actions (I5)

No new API is required. The confirmation screen uses the existing label endpoint (retaining the 2-hour fingerprint-bound account token headers) and keeps shipment confirmation separate from label readiness.

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
