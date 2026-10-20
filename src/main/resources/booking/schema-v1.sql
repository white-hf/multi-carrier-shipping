-- Initial local-demo schema. Keep migrations explicit when changing persisted schema later.
CREATE TABLE IF NOT EXISTS shipment_bookings (
    booking_id TEXT PRIMARY KEY NOT NULL,
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL COLLATE BINARY,
    idempotency_key TEXT NOT NULL COLLATE BINARY,
    request_hash TEXT NOT NULL,
    request_json TEXT NOT NULL,
    carrier_id TEXT NOT NULL,
    customer_reference TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('PENDING','CONFIRMED','FAILED','PENDING_INQUIRY','MANUAL_REVIEW')),
    carrier_booking_id TEXT,
    tracking_number TEXT,
    label_url TEXT,
    response_json TEXT,
    error_code TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    next_inquiry_at INTEGER NOT NULL,
    inquiry_attempts INTEGER NOT NULL DEFAULT 0,
    lease_token TEXT,
    lease_expires_at INTEGER NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_owner_idempotency UNIQUE (owner_type, owner_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_booking_inquiry
    ON shipment_bookings(status, next_inquiry_at, lease_expires_at, booking_id);
