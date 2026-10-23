# Shipping workflow prototype

[Open the standalone prototype](shipping-workflow.html) in a browser. It requires no build tools or server. The source requirements are in the [PRD](../prd/shipping-workflow.md).

Journey: Shipping Details → Compare Rates → Review & Confirm → simulated Shipment Confirmation.

This is a **mock-only product reference**, not the working frontend. Rates are fixed fixtures, submission produces fixed demo identifiers, and the label button is disabled. It makes no external API calls and creates no shipments. Form inputs remain on the page when navigating backward.

The working product must use returned quotes, creation results and label URLs. Loading, empty, error and pending-confirmation states are required by the PRD but are not demonstrated by this prototype. It currently offers only Ontario/Nova Scotia in its province selectors and omits several optional product fields.

Frontend implementation should remain minimal and serve end-to-end acceptance. No separate frontend technical design is planned. [Requirements review](../prd/requirements-review.md) records gaps and discussion points.
