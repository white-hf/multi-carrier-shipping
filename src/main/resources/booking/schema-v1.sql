-- Current development schema. Use fresh database files after schema changes; no legacy migration is provided.
CREATE TABLE IF NOT EXISTS shipment_bookings (
    -- Primary identifier and multi-tenancy
    booking_id TEXT PRIMARY KEY NOT NULL,          -- Globally unique booking identifier (UUIDv7 time-ordered)
    owner_type TEXT NOT NULL,                      -- Tenant or entity type owning this booking (e.g., 'MERCHANT')
    owner_id TEXT NOT NULL COLLATE BINARY,         -- Tenant identifier, case-sensitive binary collation
    idempotency_key TEXT NOT NULL COLLATE BINARY,  -- Client-supplied idempotency key for deduplication
    request_hash TEXT NOT NULL,                    -- SHA-256 fingerprint of the normalized request payload

    -- Shipment parcel and service intent
    order_reference TEXT NOT NULL,                 -- Merchant's internal order or invoice reference
    service_level TEXT NOT NULL,                   -- Carrier service speed tier (e.g., 'PRIORITY', 'STANDARD')
    origin_postal_code TEXT NOT NULL,              -- Normalized origin postal/ZIP code
    destination_postal_code TEXT NOT NULL,         -- Normalized destination postal/ZIP code
    origin_country_code TEXT NOT NULL,             -- ISO 3166-1 alpha-2 origin country code
    destination_country_code TEXT NOT NULL,        -- ISO 3166-1 alpha-2 destination country code
    -- SQLite NUMERIC affinity may coerce decimals to REAL. Canonical decimal text preserves exact intent.
    weight_kg TEXT NOT NULL,                       -- Parcel weight in kilograms, stored as exact canonical text
    length_cm TEXT NOT NULL,                       -- Parcel length in centimeters, stored as exact canonical text
    width_cm TEXT NOT NULL,                        -- Parcel width in centimeters, stored as exact canonical text
    height_cm TEXT NOT NULL,                       -- Parcel height in centimeters, stored as exact canonical text

    -- Routing and carrier references
    carrier_id TEXT NOT NULL,                      -- Normalized carrier identifier (e.g., 'fedex', 'ups')
    booking_locator TEXT NOT NULL,                 -- Internal routing locator for partition/shard lookup
    carrier_context_reference TEXT NOT NULL,       -- Account/context reference used for carrier routing
    customer_reference TEXT NOT NULL UNIQUE,       -- Unique external reference submitted to the carrier

    -- Lifecycle state and fulfillment outcomes
    status TEXT NOT NULL CHECK (status IN ('PENDING','CONFIRMED','FAILED','PENDING_INQUIRY','MANUAL_REVIEW')), -- Aggregate lifecycle status
    carrier_booking_id TEXT,                       -- Carrier-assigned proprietary booking/confirmation ID
    tracking_number TEXT,                          -- Tracking or waybill number assigned by the carrier
    label_url TEXT,                                -- URL to download or print the generated shipping label
    error_code TEXT,                               -- Failure reason or diagnostic code if rejected/suspended

    -- Timestamps, deadlines, and optimistic concurrency
    created_at INTEGER NOT NULL,                   -- Creation epoch timestamp in milliseconds
    updated_at INTEGER NOT NULL,                   -- Last modification epoch timestamp in milliseconds
    submission_deadline_at INTEGER NOT NULL,       -- Deadline epoch timestamp for carrier submission
    version INTEGER NOT NULL DEFAULT 0,            -- Optimistic locking version for concurrency control

    CONSTRAINT uk_owner_idempotency UNIQUE (owner_type, owner_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_booking_unresolved
    ON shipment_bookings(created_at, booking_id, submission_deadline_at)
    WHERE status IN ('PENDING','PENDING_INQUIRY');
