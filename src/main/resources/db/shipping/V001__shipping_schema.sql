-- Initial release baseline: four business tables with final types, constraints and indexes.
-- Includes known-unsent booking retries. Intended for a new database, not a legacy upgrade.

CREATE TABLE shipment_requests (
  request_id VARCHAR(36) PRIMARY KEY NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  sender_contact_name VARCHAR(128) NOT NULL,
  sender_company_name VARCHAR(200),
  sender_address_line1 VARCHAR(255) NOT NULL,
  sender_address_line2 VARCHAR(255),
  sender_city VARCHAR(128) NOT NULL,
  sender_province VARCHAR(64) NOT NULL,
  sender_postal_code VARCHAR(20) NOT NULL,
  sender_country_code CHAR(2) NOT NULL,
  sender_phone VARCHAR(32) NOT NULL,
  sender_email VARCHAR(254),
  sender_residential SMALLINT NOT NULL CHECK (sender_residential IN (0, 1)),
  recipient_contact_name VARCHAR(128) NOT NULL,
  recipient_company_name VARCHAR(200),
  recipient_address_line1 VARCHAR(255) NOT NULL,
  recipient_address_line2 VARCHAR(255),
  recipient_city VARCHAR(128) NOT NULL,
  recipient_province VARCHAR(64) NOT NULL,
  recipient_postal_code VARCHAR(20) NOT NULL,
  recipient_country_code CHAR(2) NOT NULL,
  recipient_phone VARCHAR(32) NOT NULL,
  recipient_email VARCHAR(254),
  recipient_residential SMALLINT NOT NULL CHECK (recipient_residential IN (0, 1)),
  parcel_length DECIMAL(12, 4) NOT NULL CHECK (parcel_length > 0),
  parcel_width DECIMAL(12, 4) NOT NULL CHECK (parcel_width > 0),
  parcel_height DECIMAL(12, 4) NOT NULL CHECK (parcel_height > 0),
  parcel_weight DECIMAL(12, 4) NOT NULL CHECK (parcel_weight > 0),
  dimension_unit VARCHAR(8) NOT NULL CHECK (dimension_unit IN ('IN', 'CM')),
  weight_unit VARCHAR(8) NOT NULL CHECK (weight_unit IN ('LB', 'KG')),
  package_type VARCHAR(32) NOT NULL,
  shipping_date VARCHAR(10) NOT NULL,
  state VARCHAR(32) NOT NULL CHECK (state IN ('RATING', 'READY', 'NO_RATES', 'RATE_FAILED')),
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  idempotency_key VARCHAR(64),
  content_fingerprint CHAR(64),
  rate_version BIGINT NOT NULL DEFAULT 0 CHECK (rate_version >= 0),
  rate_deadline_at BIGINT,
  UNIQUE (user_id, request_id)
);

CREATE UNIQUE INDEX uk_shipment_request_key ON shipment_requests(user_id, idempotency_key);

CREATE TABLE shipment_quotes (
  quote_id VARCHAR(36) PRIMARY KEY NOT NULL,
  request_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  provider_id VARCHAR(64) NOT NULL,
  account_alias VARCHAR(64) NOT NULL,
  provider_quote_id VARCHAR(255),
  carrier_id VARCHAR(128) NOT NULL,
  carrier_name VARCHAR(200),
  service_id VARCHAR(128) NOT NULL,
  service_name VARCHAR(200),
  total_minor BIGINT NOT NULL CHECK (total_minor >= 0),
  currency CHAR(3) NOT NULL,
  transit_days SMALLINT CHECK (transit_days IS NULL OR transit_days >= 0),
  expires_at BIGINT NOT NULL,
  FOREIGN KEY (user_id, request_id) REFERENCES shipment_requests(user_id, request_id)
);

CREATE INDEX idx_request_quotes ON shipment_quotes(user_id, request_id);

CREATE TABLE shipment_bookings (
  booking_id VARCHAR(36) PRIMARY KEY NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  request_id VARCHAR(36) NOT NULL,
  quote_id VARCHAR(36) NOT NULL,
  provider_id VARCHAR(64) NOT NULL,
  account_alias VARCHAR(64) NOT NULL,
  provider_quote_id VARCHAR(255) NOT NULL,
  carrier_id VARCHAR(128) NOT NULL,
  service_id VARCHAR(128) NOT NULL,
  accepted_minor BIGINT NOT NULL CHECK (accepted_minor >= 0),
  currency CHAR(3) NOT NULL,
  quote_expires_at BIGINT NOT NULL,
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
  UNIQUE (user_id, idempotency_key),
  UNIQUE (user_id, request_id)
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
