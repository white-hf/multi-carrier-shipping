# Code Style, Formatting & Readability Guide

**Date:** 2026-10-24  
**Scope:** Java 17 Backend (`src/main/java`, `src/test/java`) & Vanilla JS/CSS Frontend (`src/main/resources/static`)

---

## 1. Purpose

Production shipping and financial workflows must be auditable at a glance during code reviews and incident debugging. Dense, single-line "wall of code" formatting obscures control flow, transaction boundaries, and concurrency invariants. Every source file in this repository must follow the four formatting rules below.

---

## 2. Core Formatting Rules

### Rule 1: One Statement Per Line & Explicit Braces (`{ ... }`)
- **Never** chain multiple statements (`a = 1; b = 2;`) or multiple field declarations on a single line.
- **Never** compress constructors, methods, or `if / else / for / try / catch` blocks onto a single line.
- Always use explicit curly braces `{ ... }` for control-flow blocks, even for single-statement branches.

**Bad (Compressed):**
```java
public final class SandboxLabelAdapter implements LabelProviderPort {
    private final SandboxProviderClient client;
    public SandboxLabelAdapter(SandboxProviderClient client) { this.client = client; }
    @Override public boolean allowedUrl(String url) { return client.allowedUrl(url); }
}
```

**Good (Readable):**
```java
public final class SandboxLabelAdapter implements LabelProviderPort {

    private final SandboxProviderClient client;

    public SandboxLabelAdapter(SandboxProviderClient client) {
        this.client = client;
    }

    @Override
    public boolean allowedUrl(String url) {
        return client.allowedUrl(url);
    }
}
```

---

### Rule 2: Logical Paragraphing & Numbered Orchestration Steps
- Separate distinct logical phases inside a method with a single blank line.
- In orchestration methods (`QuoteApplicationService`, `ShipmentApplicationService`, `PendingShipmentDiscovery`, `SandboxProviderClient`), prefix each major phase with a concise numbered comment (`// 1. ...`, `// 2. ...`) so readers can immediately trace transaction and network boundaries.

**Good:**
```java
// 1. Enforce strict syntactic format on the incoming idempotency key
if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}")) {
    throw ShippingException.badRequest("INVALID_IDEMPOTENCY_KEY");
}

// 2. Perform a fast read optimized for common replay cases
Optional<ShipmentStore.Record> prior = store.byKey(user, key);
if (prior.isPresent()) {
    return replay(prior.get(), hash);
}

// 3. Resolve and validate the quote selection owned by this user
var selection = quotes.resolve(user, quoteId);
```

---

### Rule 3: Multi-Line Record, Constructor & SQL Alignment
- When a `record`, constructor, or method signature has more than 3 parameters (or exceeds ~100 characters), place each parameter on its own indented line.
- Format all SQL statements using Java text blocks (`"""`) with explicit column lists grouped logically per line. Never use `SELECT *`.

**Good:**
```java
public record RateRequest(
        String userId,
        String requestId,
        String key,
        String fingerprint,
        ShippingIntent intent,
        State state,
        long version,
        Long deadline) {
    // ...
}
```

---

### Rule 4: Comments Explain *Why* (Invariants & Trade-offs), Never *What*
- Do not write comments that merely restate the code (e.g., `// Set status to CONFIRMED`).
- Write comments that explain **why** a non-obvious design choice exists:
  - Why `@Transactional(propagation = Propagation.NEVER)` is required around external HTTP calls.
  - Why Compare-And-Set (`WHERE booking_id = ? AND version = ?`) prevents stale threads from overwriting a newer state.
  - Why an ambiguous timeout transitions to `PENDING_INQUIRY` / `MANUAL_REVIEW` rather than retrying a chargeable POST.

---

## 3. Pre-Commit Checklist

Before committing any Java, JavaScript, or SQL change:
1. Verify no class uses compressed single-line methods or semicolon-chained statements.
2. Verify all imports are explicit (avoid wildcard `import foo.*;` in production classes).
3. Run `./mvnw test` (including `ModuleBoundariesTest`) to ensure architectural and formatting cleanliness.

## 4. Formatting-Only Changes and Future Code

Apply the formatting rules to new and modified backend Java code, including tests and performance utilities.

For a formatting-only task, preserve names, imports, method and class structure, expressions, SQL string contents, exception handling, transaction boundaries, and execution order. Do not add explanatory comments or refactor logic. Existing SQL strings may remain in their original representation; use text blocks for new multiline SQL. Add braces without changing branch ownership or loop scope.

Use four-space indentation. Put declaration annotations on separate lines, expand executable bodies, and place each parameter on a separate line for declarations with more than three parameters. Empty data-only records may remain compact. Frontend changes require separate authorization.

Verify formatting-only changes against the pre-format working copy rather than HEAD, because unrelated functional work may already be uncommitted. Run the backend test suite after formatting.
