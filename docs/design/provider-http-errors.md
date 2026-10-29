# Provider HTTP Error Handling

Shared transport returns status, headers and bounded body bytes. The provider adapter interprets the documented operation contract before requiring a success body. It never blindly retries a write.

| Operation | Status | Interpretation |
| --- | --- | --- |
| Rates | 200 | Validate availableRates; an empty array is NO_RATES |
| Rates | 404 | Documented no-rates result, including an empty response body |
| Rates | 400 | PROVIDER_REQUEST_REJECTED (INVALID_INPUT -> HTTP 400 to web client); ignored by the availability circuit |
| Rates | 401 | PROVIDER_AUTHENTICATION_FAILED; integration failure, HTTP 503 to our client |
| Rates/read | 429 | PROVIDER_RATE_LIMITED; no automatic retry |
| Rates/read | 5xx | PROVIDER_UNAVAILABLE |
| Rates | Other statuses | PROVIDER_UNEXPECTED_RESPONSE; no invented success semantics |
| Create | 200/201 | Confirm only with a valid creation receipt; label/tracking may be absent |
| Create | 400 | Definitive validation/parameter rejection (HTTP 422 REJECTED) even without a response body |
| Create | 5xx or other non-200/201/400 status | Unknown creation outcome (HTTP 202 PENDING_CONFIRMATION); never automatically resubmit |
| Create | Success status, invalid receipt | Unknown creation outcome |

RETRYABLE remains strictly reserved for local admission refusal before any HTTP request is dispatched (`SHIPMENT_SUBMISSION_NOT_DISPATCHED`). Provider HTTP status handling stays inside `provider/aggregator` and `shared/infrastructure/http` without polluting `booking` domain or application layers.

## Backend diagnostics

Non-2xx response logs retain status and filtered error content with request correlation. Error bodies are limited to 16 KiB before filtering and approximately 2 KiB in logs; known JSON diagnostic fields are retained, unknown fields are redacted, message text is sanitized, arrays/depth are bounded, and HTML/binary or malformed JSON is omitted. Body-read errors retain the received status. Transport failures include their typed reason, never a misleading httpStatus=null. Credentials, headers and full request payloads are excluded. Frontend errors retain stable codes (`PROVIDER_REQUEST_REJECTED` prompts the user to check shipping details) and do not expose raw provider messages.
