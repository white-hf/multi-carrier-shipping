-- Independently owned work queue; no foreign key or join to the booking service's tables.
CREATE TABLE IF NOT EXISTS booking_reconciliation_tasks (
    -- Task identity and routing linkage
    task_id TEXT PRIMARY KEY NOT NULL,                     -- Unique recovery task identifier (UUIDv7 time-ordered)
    booking_id TEXT NOT NULL,                              -- Associated booking identifier to reconcile
    booking_locator TEXT NOT NULL,                         -- Routing locator matching the booking partition/shard
    carrier_id TEXT NOT NULL,                              -- Normalized shipping carrier identifier (e.g., 'fedex', 'ups')
    carrier_context_reference TEXT NOT NULL,               -- Account/tenant context reference required for carrier inquiry routing
    customer_reference TEXT NOT NULL,                      -- Unique external customer reference used to query the carrier

    -- Scheduling, execution state, and distributed lease
    state TEXT NOT NULL CHECK(state IN ('WAITING','RUNNING','RESULT_PENDING','PAUSED')), -- Work queue execution state
    available_at INTEGER NOT NULL,                         -- Scheduled epoch timestamp when this task becomes eligible to run
    attempts INTEGER NOT NULL DEFAULT 0,                   -- Number of inquiry attempts completed so far
    lease_token TEXT,                                      -- Distributed lease token fencing the active worker instance
    version INTEGER NOT NULL DEFAULT 0,                    -- Optimistic locking version for concurrency control and fencing
    last_error_code TEXT,                                  -- Diagnostic error code from the most recent inquiry attempt

    -- Reconciled carrier outcomes (populated upon successful inquiry)
    result_type TEXT CHECK(result_type IN ('FOUND','UNRESOLVED','EXHAUSTED')), -- Reconciliation outcome classification
    carrier_booking_id TEXT,                               -- Recovered carrier booking confirmation ID
    tracking_number TEXT,                                  -- Recovered parcel tracking number assigned by the carrier
    label_url TEXT,                                        -- Recovered URL to download or print the shipping label

    -- Timestamps
    created_at INTEGER NOT NULL,                           -- Task creation epoch timestamp in milliseconds
    updated_at INTEGER NOT NULL,                           -- Task last update epoch timestamp in milliseconds

    UNIQUE(booking_locator,booking_id)
);
CREATE INDEX IF NOT EXISTS idx_reconciliation_due
    ON booking_reconciliation_tasks(available_at,task_id)
    WHERE state IN ('WAITING','RUNNING','RESULT_PENDING');
