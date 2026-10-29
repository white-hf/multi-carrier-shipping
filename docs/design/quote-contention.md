# Quote Execution Contention

A request may attempt one insert or one retry claim. A losing caller never acquires another claim in the same invocation.

- Initial insert conflict: read the winner once, check the canonical fingerprint, and return its read-only projection with its real request ID. A READY winner may require reading offers. Do not refresh or expire its execution.
- Retry claim conflict: return HTTP 202 with `state: "CONTENDED"`, the known `requestId`, an empty quotes list, `Location: /api/quotes/{requestId}`, and `Retry-After: 2`.
- CONTENDED is a response-only observation, not a persisted request state. It means this call did not obtain execution rights, not that another provider call is guaranteed to be running. Expiry or another transition may also reject a claim.
- Completion expiry: in the completion transaction, attempt to fail only the original overdue claim using its version fence. Success returns RATE_FAILED without rereading. If the fence loses, return the repository's read-only current snapshot; never expire the newer snapshot or dispatch again. The service performs no generic timeout repair after completion.
- Ordinary status GET retains its existing overdue-execution handling. Provider deadlines, version fences, atomic quote replacement and stored states are unchanged.

## Frontend integration

Handle CONTENDED as a prompt to GET the known request ID. A bounded query/retry flow must recover if the original response is lost. Do not clear existing quotes or replace a later successful result with a delayed contention response. Scope response handling to the active request/key; an obsolete operation must not update a newer form. Frontend changes are delivered separately.

## Verification

Tests force two initial empty reads before competing inserts, assert a single provider execution and a shared persisted identity, reject conflicting fingerprints, and verify retry losers do not reread or mutate state. A completion-conflict test verifies an overdue newer snapshot is not repaired by the old completion handler.

## Completion diagnostics

The internal completion outcome distinguishes PUBLISHED, EXPIRED (the original version was committed as RATE_FAILED), and STALE (no completion write won its fence). The application logs a warning for committed EXPIRED and an informational stale-result message for STALE. It logs only after the store transaction returns; failed commits must not produce a successful-expiry warning. These internal outcomes do not change API states or metrics.

A failed provider execution also owns only its original version fence. If recording failure loses that fence, it reads the latest request directly and never runs status-query expiry repair against the newer execution, even if that newer snapshot is overdue.
