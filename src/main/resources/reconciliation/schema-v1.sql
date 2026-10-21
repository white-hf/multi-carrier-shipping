-- Independently owned work queue; no foreign key or join to the booking service's tables.
CREATE TABLE IF NOT EXISTS booking_reconciliation_tasks (
    task_id TEXT PRIMARY KEY NOT NULL,
    booking_id TEXT NOT NULL,
    booking_locator TEXT NOT NULL,
    carrier_id TEXT NOT NULL,
    carrier_context_reference TEXT NOT NULL,
    customer_reference TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('WAITING','RUNNING','RESULT_PENDING','PAUSED')),
    available_at INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_token TEXT,
    version INTEGER NOT NULL DEFAULT 0,
    last_error_code TEXT,
    result_type TEXT CHECK(result_type IN ('FOUND','UNRESOLVED','EXHAUSTED')),
    carrier_booking_id TEXT,
    tracking_number TEXT,
    label_url TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(booking_locator,booking_id)
);
CREATE INDEX IF NOT EXISTS idx_reconciliation_due
    ON booking_reconciliation_tasks(available_at,task_id)
    WHERE state IN ('WAITING','RUNNING','RESULT_PENDING');
