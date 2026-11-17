# Application API — Frontend & Backend Protocol Contract

Status (2026-11-16): **I10 implemented contract; current-source verification is recorded in the I10 delivery summary.** Authentication and label behavior are retained. Runtime uses stateless quotes and provider-quote booking identity on a new database baseline; historical database migration is out of scope. All paths are relative to the backend origin (`http://127.0.0.1:8080`). See the [I10 plan](../iterations/iteration-10-stateless-quotes.md) for provider gates and acceptance.

---

## 1. Protocol & Response Envelope Convention

This API follows a **RESTful HTTP-native protocol convention** (aligned with Stripe / GitHub / RFC 9457 Problem Details) rather than wrapping every response in a `200 OK { code, msg, data }` envelope:

1. **Success Responses (`200 OK`, `201 Created`, `202 Accepted`) — Unwrapped Resource Payload**
   - HTTP status codes convey the exact operation semantics (`201` newly created resource, `200` idempotent replay or read, `202` in-flight / pending confirmation).
   - The JSON response body is the **business resource object directly** (no outer `data` wrapper).
   - Diagnostic and routing metadata are carried in standard HTTP headers:
     - `X-Request-Id`: Time-ordered UUIDv7 correlation ID generated for every request.
      - `Location`: Canonical booking status URI (`/api/bookings/{bookingId}`). Stateless quote responses have no resource Location.
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
          "statusUrl": "/api/bookings/01a17114-61ea-76bc-9adc-3df32a852375"
       }
     }
     ```
   - `code`: Bounded, machine-readable uppercase error code (never raw stack traces or upstream provider bodies).
   - `message`: Human-readable summary derived safely from `code`.
   - `requestId`: Matches the `X-Request-Id` response header for log correlation.
   - `fieldErrors`: Array of `{ field, message }` objects for inline form highlighting (`[]` when not a field validation error).
    - `operation` *(optional)*: Included when a booking is already persisted, for example a known-unsent retry refusal. Rate errors never carry a persisted quote-operation locator.

| HTTP Status | Category | Representative `code` Values |
| --- | --- | --- |
| `200 OK` | Read / Idempotent Replay | Returns resource JSON directly (or `application/pdf` bytes for ready labels) |
| `201 Created` | Initial Confirmed Booking | Returns newly confirmed booking JSON + `Location` header |
| `202 Accepted` | Pending Booking / Label | In-flight or uncertain booking, or pending label + bounded polling guidance |
| `400 Bad Request` | Validation Failure | `INVALID_REQUEST`, `INVALID_SHIPPING_DATE`; no missing/invalid idempotency-key errors |
| `401 Unauthorized` | Auth / Token Failure | `UNAUTHORIZED` (missing, expired, or fingerprint-mismatched account token) |
| `429 Too Many Requests` | Login / API Quota | `AUTH_RATE_LIMITED`, `API_RATE_LIMITED` + `Retry-After` |
| `403 Forbidden` | Cross-Origin Write | `ORIGIN_NOT_ALLOWED` |
| `404 Not Found` | Missing / Foreign Owner | `RESOURCE_NOT_FOUND` |
| `409 Conflict` | Retry Not Permitted / Basic Admission | Non-RETRYABLE explicit retry, or a new submission rejected by retained basic date/expiry admission; never a random-key conflict |
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
and 20 label GETs/HEADs per minute. Duplicate create calls and explicit retry sends count toward booking admission; status/lookup remain reads. `429 AUTH_RATE_LIMITED` or
`429 API_RATE_LIMITED` includes integer `Retry-After` seconds. Limits are per process, with a
bounded 4096-entry table that rejects new keys while full and reclaims expired entries.

The frontend requires explicit sign-in, never refreshes anonymously, and never automatically
replays a write on 401. Switching accounts clears drafts/operation caches; the same account can
re-authenticate after expiry and recover its active operation. All operations use the backend; local
demo mode and sample-data execution are removed. See [Account access deployment](../runbooks/account-access.md) for credentials and proxy setup.

## Rates

`POST /api/quotes` requires no `Idempotency-Key`. Validate input → provider rate call → normalization → return. No business-table writes occur on any result or error path.

```json
{
  "sender": {"contactName":"Test Sender","addressLine1":"100 Test Street","city":"Toronto","province":"ON","postalCode":"M5V 3L9","countryCode":"CA","phone":"4165550100"},
  "recipient": {"contactName":"Test Recipient","addressLine1":"200 Test Street","city":"Halifax","province":"NS","postalCode":"B3J 3K1","countryCode":"CA","phone":"9025550100"},
  "parcel": {"length":12,"width":10,"height":8,"dimensionUnit":"IN","weight":5,"weightUnit":"LB","packageType":"PACKAGE"}
}
```

Optional address fields: `companyName`, `addressLine2`, `email`, `residential` (false default). All 13 Canadian province/territory codes are supported. Optional `shippingDate` is `yyyy-MM-dd`; when omitted, the backend initializes it in the configured business timezone. Dimensions and weight must be positive. Alternative units: CM and KG.

Return `200` with the normalized list; an empty result is `quotes: []`. Keep existing shipping-date/input validation, external deadlines, quotas, concurrency and circuit protection. There is no RATING/CONTENDED state, request version, execution takeover or persisted failure recovery. Remove `GET /api/quotes/{requestId}`; it is not a compatibility alias for a new rate call.

```json
{
  "shippingDate":"2026-10-23",
  "quotes":[{
    "providerId":"sandbox","providerQuoteId":"opaque-provider-quote-id",
    "carrierId":"14","carrierName":"Carrier",
    "serviceId":"1","serviceName":"Ground",
    "total":{"amount":"27.35","currency":"CAD"},
    "transitDays":2,"expiresAt":"2026-10-23T15:13:00Z","expirySource":"LOCAL_ESTIMATE"
  }]
}
```

Values are illustrative, not provider contract evidence. `expiresAt` is nullable when unavailable; `expirySource` is `PROVIDER`, `LOCAL_ESTIMATE` or null. Do not invent transit estimates or treat a local estimate as guaranteed validity. Provider quote IDs are exposed intentionally as opaque selection identities; preserve exact case/content and URL-encode them on lookup. Backend credentials/account aliases are never returned.

Use `(providerId, providerQuoteId)` to select/match offers. Input edits clear the unsubmitted selection and advance a local request sequence. Ignore stale success/error responses from an older input revision/session. Failure or refresh starts a new rate query, never restores a historical rate task. Correlation `X-Request-Id` remains unrelated to quote identity.

## Create and read booking

`POST /api/bookings` requires no `Idempotency-Key` and accepts no internal `quoteId`:

```json
{
  "providerId":"sandbox",
  "providerQuoteId":"opaque-provider-quote-id",
  "shippingInput":{
    "sender":{"contactName":"Test Sender","addressLine1":"100 Test Street","city":"Toronto","province":"ON","postalCode":"M5V 3L9","countryCode":"CA","phone":"4165550100"},
    "recipient":{"contactName":"Test Recipient","addressLine1":"200 Test Street","city":"Halifax","province":"NS","postalCode":"B3J 3K1","countryCode":"CA","phone":"9025550100"},
    "parcel":{"length":12,"width":10,"height":8,"dimensionUnit":"IN","weight":5,"weightUnit":"LB","packageType":"PACKAGE"},
    "shippingDate":"2026-10-23"
  },
  "displaySnapshot":{
    "carrierId":"14","carrierName":"Carrier","serviceId":"1","serviceName":"Ground",
    "total":{"amount":"27.35","currency":"CAD"},
    "expiresAt":"2026-10-23T15:13:00Z","expirySource":"LOCAL_ESTIMATE"
  }
}
```

The input/display fields are application names; the current sandbox adapter sends only providerQuoteId to create. Provider ID uniqueness/reuse and deployed field guarantees still require external verification before release. Current ID admission accepts nonblank opaque IDs up to 255 characters without control characters and preserves exact case/content. Missing estimates/expiry/display names remain nullable. Amounts use exact decimals and currency validation, never floating-point calculations. No submitted amount/service/input is treated as provider-confirmed fact. No signature or user-to-quote ownership proof is required.

Resolve userId from authentication, and provider account/credentials from configuration. First validate identity/provider, then return an existing owned booking if present, ignoring replacement snapshot semantics and never overwriting saved facts. For a new booking, validate the complete input/display snapshot and commit PENDING before dispatch. Only the initial unique insert winner calls create; database failure prevents dispatch. A uniqueness loser reads and returns the winner. No `quotes.resolve()` or local quote persistence is used.

Default uniqueness is `(user_id, provider_id, provider_quote_id)`. Before release verify ID scope/reuse; if only account-unique, include a stable backend account identifier in storage/lookup/routing. Browser cannot select it. Different quote IDs may each create a booking, even for identical inputs.

Initial confirmed success is `201`; initial definite rejection is `422`; pending/unknown submission is `202`. Ordinary create returning an existing booking is `200` for CONFIRMED/REJECTED/RETRYABLE/MANUAL_REVIEW, or `202` for PENDING/PENDING_CONFIRMATION. It never retries an existing RETRYABLE row. Include booking `Location`; only in-progress/unknown results receive polling guidance. A third-party rejection or invalid success receipt is never success.

```json
{
  "bookingId":"internal-booking-id","providerId":"sandbox","providerQuoteId":"opaque-provider-quote-id",
  "status":"CONFIRMED","shipmentId":"provider-shipment-id",
  "trackingNumber":null,"labelStatus":"PENDING",
  "labelUrl":"/api/bookings/internal-booking-id/label",
  "errorCode":null,"statusUrl":"/api/bookings/internal-booking-id"
}
```

Status values: `PENDING`, `RETRYABLE`, `CONFIRMED`, `REJECTED`, `PENDING_CONFIRMATION`, `MANUAL_REVIEW`. Preserve existing receipt validation and label behavior. A confirmed booking may have no tracking or label yet. Unknown creation cannot be shown as rejection or permission to resend. Provider-confirmed actual amount/service is separate from the submitted snapshot and remains absent unless returned.

When recovery cannot safely query or retry the provider, the existing booking becomes `MANUAL_REVIEW`:

```json
{
  "bookingId":"internal-booking-id","status":"MANUAL_REVIEW","shipmentId":null,
  "trackingNumber":null,"labelStatus":"PENDING","labelUrl":null,
  "errorCode":"AUTHORITATIVE_LOOKUP_UNSUPPORTED",
  "statusUrl":"/api/bookings/internal-booking-id"
}
```

Status reads and existing manual-review create replays return `200`, without automatic polling. This is unresolved, not success. Hide sending controls for that booking, stop polling, retain its query entry and support guidance. Refresh may read status once without restarting polling. The user may start a separate quote/shipment while the original unresolved operation remains accessible; this does not resolve it or guarantee cross-quote duplicate protection. No manual resolution endpoint is added.

Recovery uses provider/account capabilities. Reasons distinguish `AUTHORITATIVE_LOOKUP_UNSUPPORTED`, `AUTHORITATIVE_LOOKUP_UNVERIFIED`, and `AUTOMATIC_RECOVERY_UNAVAILABLE`; existing simulator inquiry exhaustion remains `INQUIRY_EXHAUSTED`. Capability metadata alone never enables a creation retry.

### Read-only status recovery

- `GET /api/bookings/{bookingId}` returns `200` with current owned state, or `404` for missing/foreign records.
- `GET /api/bookings/lookup?providerId=...&providerQuoteId=...` returns the same projection for the current user's existing booking, or `404`. Route the static `lookup` path distinctly from `{bookingId}`. Query values must be URL-encoded and must preserve opaque quote IDs.
- Both GETs are read-only: no claim, status transition, task registration or provider create. Quote expiry does not prevent reading a booking. Scheduled discovery is separate from these reads.
- Without a row, retain the original provider quote identity and inform the user that an explicit initial-submit retry is possible. Never automatically POST from lookup/refresh; an earlier request may commit after a 404.
- Input/expiry validation for a new booking must not block an existing identity replay. Same-identity duplicate payloads never replace the originally saved snapshot.

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

`GET /api/bookings/{id}` returns RETRYABLE and the same error code. A distinct user action calls **`POST /api/bookings/{bookingId}/retry`**, with no new quote, replacement snapshot or idempotency key. Backend uses the original saved account/quote/input, retained basic eligibility rules and a CAS on owner, booking ID, RETRYABLE state and version. Only one claim winner sends. A competing retry returns current state and does not dispatch; disallowed states return a state conflict. Confirmed retry execution returns `200`, definite rejection `422`, pending/unknown `202`, and repeated proven-unsent refusal `503` with the same operation locator.

The UI labels this action **Retry sending** and shows it only for RETRYABLE. Reload and **Check status** issue GETs only; RETRYABLE stops polling. PENDING, unknown, MANUAL_REVIEW and terminal states cannot resend. Provider makes the final quote-validity decision; do not resolve a local quote or add signed credentials. An expiry/admission refusal never removes the saved booking or resets uncertainty.

### Browser operation persistence

Save a versioned active operation before the POST: userId, providerId, providerQuoteId, optional bookingId, status and necessary recovery/submission information. Maintain query entries for earlier unresolved operations when beginning another one. Local operation storage is account-scoped and cleared on logout/account switch; no token, credentials or PDF bytes belong in it. Use business identity, session and a local request sequence to reject stale asynchronous responses, not `state.key`.

Legacy entries with bookingId recover via status GET. Never use an old internal quoteId as providerQuoteId. An older record with neither bookingId nor valid provider identity must explicitly explain that automatic recovery is unavailable and advise verification/support; it must not create automatically. Expiry clears only unsubmitted selection, never unresolved operations. Polling caps, manual-review handling and label behavior remain intact.

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
- Labels are retrieved automatically at most once for the active operation. After refresh, explicitly reload the label because browser-memory files are gone. Retry/reload never creates another shipment.

## Errors and request correlation

```json
{"code":"INVALID_REQUEST","message":"INVALID REQUEST","requestId":"correlation-id","fieldErrors":[{"field":"parcel.weight","message":"must be greater than 0"}]}
```

Use `fieldErrors` for inline validation; preserve entered values. Provider read operations can return `502/503/504` for failure/capacity/deadline. A failed HTTP creation response does not prove no shipment was created: retain the booking/provider quote identity and recover using GET. `requestId` is a diagnostic correlation ID, not the removed internal shipment request ID. Cross-origin writes remain rejected. Disallowed sending returns `409 BOOKING_NOT_RETRYABLE`; invalid/disabled provider quote identity returns `400 INVALID_PROVIDER_QUOTE`. Implementation in this checkout does not imply deployment or live-provider verification.
