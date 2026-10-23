-- Browser drafts and third-party records do not replace our durable business snapshot.
-- Historical requests have no client key; all new application writes require one.
ALTER TABLE shipment_requests ADD COLUMN idempotency_key TEXT;
ALTER TABLE shipment_requests ADD COLUMN content_fingerprint TEXT;
ALTER TABLE shipment_requests ADD COLUMN rate_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE shipment_requests ADD COLUMN rate_deadline_at INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uk_shipment_request_key ON shipment_requests(user_id, idempotency_key);
CREATE INDEX idx_request_quotes ON shipment_quotes(user_id, request_id);
