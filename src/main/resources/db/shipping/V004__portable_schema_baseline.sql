-- Iteration 07: RDBMS-agnostic ANSI SQL schema baseline across the four shipping tables.
-- Preserves all existing rows while replacing SQLite-only partial indexes and unbounded types.

CREATE TABLE shipment_quotes_v4_staging (
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
  expires_at BIGINT NOT NULL
);

INSERT INTO shipment_quotes_v4_staging
SELECT quote_id, request_id, user_id, provider_id, account_alias, provider_quote_id,
       carrier_id, carrier_name, service_id, service_name, total_minor, currency, transit_days, expires_at
FROM shipment_quotes;

DROP TABLE shipment_quotes;

CREATE TABLE shipment_requests_v4 (
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

INSERT INTO shipment_requests_v4 (
  request_id, user_id,
  sender_contact_name, sender_company_name, sender_address_line1, sender_address_line2,
  sender_city, sender_province, sender_postal_code, sender_country_code, sender_phone, sender_email, sender_residential,
  recipient_contact_name, recipient_company_name, recipient_address_line1, recipient_address_line2,
  recipient_city, recipient_province, recipient_postal_code, recipient_country_code, recipient_phone, recipient_email, recipient_residential,
  parcel_length, parcel_width, parcel_height, parcel_weight,
  dimension_unit, weight_unit, package_type, shipping_date, state, created_at, updated_at,
  idempotency_key, content_fingerprint, rate_version, rate_deadline_at
)
SELECT
  request_id, user_id,
  sender_contact_name, sender_company_name, sender_address_line1, sender_address_line2,
  sender_city, sender_province, sender_postal_code, sender_country_code, sender_phone, sender_email,
  CASE WHEN sender_residential IN ('1', 1, 'true', 'TRUE') THEN 1 ELSE 0 END,
  recipient_contact_name, recipient_company_name, recipient_address_line1, recipient_address_line2,
  recipient_city, recipient_province, recipient_postal_code, recipient_country_code, recipient_phone, recipient_email,
  CASE WHEN recipient_residential IN ('1', 1, 'true', 'TRUE') THEN 1 ELSE 0 END,
  length, width, height, weight,
  dimension_unit, weight_unit, package_type, shipping_date, state, created_at, updated_at,
  idempotency_key, content_fingerprint, rate_version,
  CASE WHEN state = 'RATING' AND rate_deadline_at > 0 THEN rate_deadline_at ELSE NULL END
FROM shipment_requests;

DROP TABLE shipment_requests;
ALTER TABLE shipment_requests_v4 RENAME TO shipment_requests;
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

INSERT INTO shipment_quotes (
  quote_id, request_id, user_id, provider_id, account_alias, provider_quote_id,
  carrier_id, carrier_name, service_id, service_name, total_minor, currency, transit_days, expires_at
)
SELECT quote_id, request_id, user_id, provider_id, account_alias, provider_quote_id,
       carrier_id, carrier_name, service_id, service_name, total_minor, currency, transit_days, expires_at
FROM shipment_quotes_v4_staging;

DROP TABLE shipment_quotes_v4_staging;
CREATE INDEX idx_request_quotes ON shipment_quotes(user_id, request_id);

CREATE TABLE shipment_bookings_v4 (
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
  status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'PENDING_INQUIRY', 'MANUAL_REVIEW')),
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

INSERT INTO shipment_bookings_v4
SELECT booking_id, user_id, idempotency_key, request_hash, request_id, quote_id,
       provider_id, account_alias, provider_quote_id, carrier_id, service_id,
       accepted_minor, currency, quote_expires_at, status, provider_shipment_id,
       tracking_number, label_url, label_status, error_code,
       submission_deadline_at, created_at, updated_at, version
FROM shipment_bookings;

DROP TABLE shipment_bookings;
ALTER TABLE shipment_bookings_v4 RENAME TO shipment_bookings;
CREATE INDEX idx_shipping_unresolved ON shipment_bookings(status, submission_deadline_at, booking_id);

CREATE TABLE booking_reconciliation_tasks_v4 (
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

INSERT INTO booking_reconciliation_tasks_v4
SELECT task_id, user_id, booking_id, booking_locator, provider_id, account_alias,
       customer_reference, state, available_at, last_error_code, created_at, updated_at
FROM booking_reconciliation_tasks;

DROP TABLE booking_reconciliation_tasks;
ALTER TABLE booking_reconciliation_tasks_v4 RENAME TO booking_reconciliation_tasks;
CREATE INDEX idx_shipping_tasks_due ON booking_reconciliation_tasks(state, available_at, task_id);
