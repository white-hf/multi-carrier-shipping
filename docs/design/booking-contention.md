# Booking Execution Contention

Only the caller that commits an initial insert or retry CAS may invoke the chargeable provider create operation. A caller losing either claim never attempts another claim in the same invocation.

## Initial insert conflict

Read by the original user/key to identify a winner and verify the quote fingerprint. Return that record through the existing replay response projection without entering retry. Confirmed, rejected and manual-review outcomes retain their real identity and status. A RETRYABLE winner retains HTTP 503 / SHIPMENT_SUBMISSION_NOT_DISPATCHED with its booking identity, indicating known-unsent and eligible for a later same-key retry. PENDING and PENDING_INQUIRY retain their existing HTTP 202 representation.

If no matching key exists, read by user/request to preserve the separate SHIPMENT_ALREADY_SUBMITTED conflict for a request submitted using another key. Never leak another user's record.

## Retry CAS conflict

Return HTTP 202 with a real bookingId, status CONTENDED, null receipt/label fields, Location /api/bookings/{bookingId}, and Retry-After: 2. Do not reread, revalidate, repair, or dispatch after losing the claim. CONTENDED is response-only, not a database state. It does not promise another execution exists: quote expiry may also reject the SQL fence.

The client should GET the known booking ID. Do not create a new key to recover an uncertain submission. A GET showing RETRYABLE means no create was sent and permits an explicit same-key retry if the quote remains eligible. Provider outcome persistence, late confirmations and reconciliation remain unchanged.

## Frontend integration

Prevent delayed PENDING/CONTENDED responses and polling errors from overwriting a confirmed result for the same operation. Scope callbacks to the active booking/key so old responses cannot overwrite a new operation. Handle known-unsent 503 separately from an unknown provider outcome. Support bounded status recovery when the winning response is lost. Frontend changes are delivered separately.

## Verification

Regression tests verify retry losers perform one CAS with no byId lookup or provider dispatch; initial insert losers preserve confirmed identity, enforce fingerprint checks, and report RETRYABLE without re-entering claimRetry. Existing tests cover distinct keys for one request, uncertain submissions, expired quotes and late outcome fences.
