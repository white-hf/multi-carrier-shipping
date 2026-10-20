-- Independent simulated remote state; never committed with application booking changes.
CREATE TABLE IF NOT EXISTS carrier_bookings (
    customer_reference TEXT PRIMARY KEY NOT NULL,
    request_hash TEXT NOT NULL,
    receipt_json TEXT NOT NULL,
    submission_count INTEGER NOT NULL DEFAULT 1
);
