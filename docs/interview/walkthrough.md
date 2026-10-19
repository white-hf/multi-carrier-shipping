# CTO walkthrough

## Opening — about 30 seconds

“I prepared this case study to make my technical thinking easy to follow. I started with your public shipping workflow, identified the engineering challenges, and proposed an architecture. The implementation deep dive is rate comparison. I would like to walk through the design and discuss the tradeoffs with you.”

Use this opening after the implementation is available. Until then, describe the repository as a design foundation.

## Suggested five-minute path

1. Business: a merchant needs comparable shipping options quickly.
2. Challenges: carrier latency, price consistency, account-specific rates, and partial failures.
3. Architecture: separate comparison logic from carrier-specific integrations; show the diagram.
4. Code: show the request contract, orchestration, one adapter, and error handling after review.
5. Evidence: run normal, partial-failure, and total-failure scenarios; explain one limitation.

## Short technical answers

**Why call carriers concurrently?**

“Sequential calls accumulate waiting time. Concurrent calls share the request's time budget. I also limit outbound concurrency so slow carriers cannot exhaust the application.”

**What if one carrier is slow?**

“I return valid results within the deadline and report the missing carrier. I configure transport timeouts as well as the orchestration deadline. A future timeout alone may leave the network call running.”

**Why not retry every failure?**

“Retries consume time and carrier capacity. I retry only transient, safe operations when the remaining budget allows it. Booking requires extra care because a timed-out request may already have created a shipment.”

**How do you know the cheapest rate is correct?**

“I compare eligible services using the same currency and explicit charge inclusion. I use decimal money and distinguish an estimate from a final charge. Missing tax or surcharge information must remain visible.”

**Why not make every component a microservice?**

“The module boundaries matter first. Separate deployments add operational cost. I would extract services when scaling, ownership, or release requirements justify that cost.”

**Is this how ShipTime is built?**

“I do not know your internal architecture. This is a proposed design based on your public product. I would first learn your existing constraints before recommending changes.”

## Phrases to support discussion

- “Let me show that in the code.”
- “Could you repeat the last part, please?”
- “Are you asking about correctness, performance, or the failure path?”
- “My assumption here is ____. If that changes, I would reconsider ____.”
- “That behavior is proposed; it is not implemented in this demo yet.”

## Historical project stories — pending candidate input

Add two truthful examples: a logistics backend project and a production/database problem. For each, capture the context, personal responsibility, decision, tradeoff, result, and evidence. Include metrics only if the candidate can substantiate them. Link each story to the relevant design or code discussion; this demo alone does not establish historical experience.
