-- Current development schema. Use fresh database files after schema changes; no legacy migration is provided.
CREATE TABLE IF NOT EXISTS shipment_bookings (
    booking_id TEXT PRIMARY KEY NOT NULL,
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL COLLATE BINARY,
    idempotency_key TEXT NOT NULL COLLATE BINARY,
    request_hash TEXT NOT NULL,
    order_reference TEXT NOT NULL,
    service_level TEXT NOT NULL,
    origin_postal_code TEXT NOT NULL,
    destination_postal_code TEXT NOT NULL,
    origin_country_code TEXT NOT NULL,
    destination_country_code TEXT NOT NULL,
    -- SQLite NUMERIC affinity may coerce decimals to REAL. Canonical decimal text preserves exact intent.
    weight_kg TEXT NOT NULL,
    length_cm TEXT NOT NULL,
    width_cm TEXT NOT NULL,
    height_cm TEXT NOT NULL,
    carrier_id TEXT NOT NULL,
    booking_locator TEXT NOT NULL,
    carrier_context_reference TEXT NOT NULL,
    customer_reference TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('PENDING','CONFIRMED','FAILED','PENDING_INQUIRY','MANUAL_REVIEW')),
    carrier_booking_id TEXT,
    tracking_number TEXT,
    label_url TEXT,
    error_code TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    submission_deadline_at INTEGER NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_owner_idempotency UNIQUE (owner_type, owner_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_booking_unresolved
    ON shipment_bookings(created_at, booking_id, submission_deadline_at)
    WHERE status IN ('PENDING','PENDING_INQUIRY');
