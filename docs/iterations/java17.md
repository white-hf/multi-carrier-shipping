# Java 17 rate engine iteration

Goal: make the existing local interview demo reproducible on Java 17, with bounded per-carrier resources and truthful failure outcomes.

Decisions: Spring MVC serves blocking requests; WebClient is an outbound client only. One bounded executor and connection pool per carrier. No automatic retries. Demo is loopback-only, not an authenticated production API. Booking and tracking remain design context.

Acceptance: compile on Java 17; deterministic ranking; input and carrier response validation; complete/partial/failed outcomes; deadlines cancel worker tasks; saturated carrier cannot consume another carrier's workers; breaker excludes local rejection; actionable metrics without internal error leakage.

Validation results are recorded in docs/summaries/java17.md after execution.
