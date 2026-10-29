# Provider HTTP Error Handling

Shared transport returns status, headers and bounded body bytes. The provider adapter interprets the documented operation contract before requiring a success body. It never blindly retries a write.

| Operation | Status | Interpretation |
| --- | --- | --- |
| Rates | 200 | Validate availableRates; an empty array is NO_RATES |
| Rates | 404 | Documented no-rates result, including an empty response body |
| Rates | 400 | PROVIDER_REQUEST_REJECTED; ignored by the availability circuit |
| Rates | 401 | PROVIDER_AUTHENTICATION_FAILED; integration failure, HTTP 503 to our client |
| Rates/read | 429 | PROVIDER_RATE_LIMITED; no automatic retry |
| Rates/read | 5xx | PROVIDER_UNAVAILABLE |
| Rates | Other statuses | PROVIDER_UNEXPECTED_RESPONSE; no invented success semantics |
| Create | 200/201 | Confirm only with a valid creation receipt; label/tracking may be absent |
| Create | 400 | Definitive rejection even without a response body |
| Create | 401 | Authentication failed: documented no-creation evidence, persisted RETRYABLE with a distinct error code |
| Create | 5xx or undocumented status | Unknown creation outcome; never automatically resubmit |
| Create | Success status, invalid receipt | Unknown creation outcome |

RETRYABLE means reliable evidence that creation did not occur: either local admission prevented dispatch, or the documented authentication rejection prevented creation. Only a later client call, fresh quote validation and a successful retry CAS can submit again. Authentication failures count as integration failures in the circuit; they are not successful business rejections.

## Backend diagnostics

Non-2xx response logs retain status and filtered error content with request correlation. Error bodies are limited to 16 KiB before filtering and approximately 2 KiB in logs; known JSON diagnostic fields are retained, unknown fields are redacted, message text is sanitized, arrays/depth are bounded, and HTML/binary or malformed JSON is omitted. Body-read errors retain the received status. Transport failures include their typed reason, never a misleading httpStatus=null. Credentials, headers and full request payloads are excluded. Frontend errors retain stable codes and do not expose raw provider messages.

## Frontend integration note

No frontend code is modified. The existing RETRYABLE page specifically describes local capacity rejection. It should distinguish PROVIDER_AUTHENTICATION_FAILED with a generic service-configuration/unavailable explanation rather than claim the HTTP request was never sent. Authentication error responses carry the existing operation identity for status inspection. This follow-up belongs to the frontend agent; retries are never automatic.
