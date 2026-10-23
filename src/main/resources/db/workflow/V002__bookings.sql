CREATE TABLE shipment_bookings (
 booking_id TEXT PRIMARY KEY NOT NULL,user_id TEXT NOT NULL,idempotency_key TEXT NOT NULL,request_hash TEXT NOT NULL,
 request_id TEXT NOT NULL,quote_id TEXT NOT NULL,provider_id TEXT NOT NULL,account_alias TEXT NOT NULL,provider_quote_id TEXT NOT NULL,
 carrier_id TEXT NOT NULL,service_id TEXT NOT NULL,accepted_minor INTEGER NOT NULL,currency TEXT NOT NULL,quote_expires_at INTEGER NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PENDING','CONFIRMED','FAILED','PENDING_INQUIRY','MANUAL_REVIEW')),
 provider_shipment_id TEXT,tracking_number TEXT,label_url TEXT,label_status TEXT NOT NULL,
 error_code TEXT,submission_deadline_at INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,version INTEGER NOT NULL,
 UNIQUE(user_id,idempotency_key),UNIQUE(user_id,request_id)
);
CREATE INDEX idx_workflow_unresolved ON shipment_bookings(submission_deadline_at,booking_id) WHERE status IN ('PENDING','PENDING_INQUIRY');
CREATE TABLE booking_reconciliation_tasks (
 task_id TEXT PRIMARY KEY NOT NULL,user_id TEXT NOT NULL,booking_id TEXT NOT NULL,booking_locator TEXT NOT NULL,
 provider_id TEXT NOT NULL,account_alias TEXT NOT NULL,customer_reference TEXT NOT NULL,state TEXT NOT NULL,available_at INTEGER NOT NULL,
 last_error_code TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,
 UNIQUE(user_id,booking_locator,booking_id)
);
CREATE INDEX idx_workflow_tasks_due ON booking_reconciliation_tasks(available_at,task_id) WHERE state='WAITING';
