-- Stateless quotation baseline. Existing incompatible databases fail checksum verification.
-- No business database is automatically reset or migrated to this baseline.
CREATE TABLE shipment_bookings (
    booking_id VARCHAR(36) PRIMARY KEY NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    provider_id VARCHAR(64) NOT NULL COLLATE BINARY,
    account_alias VARCHAR(64) NOT NULL,
    provider_quote_id VARCHAR(255) NOT NULL COLLATE BINARY,
    submission_snapshot TEXT NOT NULL,
    quote_expires_at BIGINT,
    provider_actual_total_minor BIGINT CHECK (provider_actual_total_minor >= 0),
    provider_actual_currency CHAR(3),
    provider_actual_service_id VARCHAR(128),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'RETRYABLE', 'CONFIRMED', 'FAILED', 'PENDING_INQUIRY', 'MANUAL_REVIEW')),
    provider_shipment_id VARCHAR(255),
    tracking_number VARCHAR(128),
    label_url TEXT,
    label_status VARCHAR(32) NOT NULL CHECK (label_status IN ('PENDING', 'AVAILABLE', 'UNAVAILABLE')),
    error_code VARCHAR(64),
    submission_deadline_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (user_id, provider_id, provider_quote_id)
);

CREATE INDEX idx_shipping_unresolved ON shipment_bookings(status, submission_deadline_at, booking_id);

CREATE TABLE booking_reconciliation_tasks (
    task_id VARCHAR(36) PRIMARY KEY NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    booking_id VARCHAR(36) NOT NULL,
    booking_locator VARCHAR(128) NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    account_alias VARCHAR(64) NOT NULL,
    customer_reference VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL CHECK (state IN ('WAITING', 'PAUSED')),
    available_at BIGINT NOT NULL,
    last_error_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (user_id, booking_id)
);

CREATE INDEX idx_shipping_tasks_due ON booking_reconciliation_tasks(state, available_at, task_id);
