CREATE TABLE relay_key (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  prefix VARCHAR(48) NOT NULL,
  secret_hash VARCHAR(64) NOT NULL,
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  models VARCHAR(2000) NOT NULL,
  monthly_budget DECIMAL(24,10) NOT NULL CHECK (monthly_budget >= 0),
  total_budget DECIMAL(24,10) NOT NULL CHECK (total_budget >= 0),
  created_at VARCHAR(40) NOT NULL
);
CREATE TABLE relay_request (
  id VARCHAR(36) PRIMARY KEY,
  key_id VARCHAR(36) NOT NULL REFERENCES relay_key(id),
  model VARCHAR(100) NOT NULL,
  endpoint VARCHAR(40) NOT NULL,
  charge_month VARCHAR(7) NOT NULL,
  price_version VARCHAR(80) NOT NULL,
  reservation DECIMAL(24,10) NOT NULL CHECK (reservation >= 0),
  cost DECIMAL(24,10) NOT NULL DEFAULT 0 CHECK (cost >= 0),
  held DECIMAL(24,10) NOT NULL CHECK (held >= 0),
  status VARCHAR(32) NOT NULL,
  input_tokens BIGINT,
  output_tokens BIGINT,
  cached_tokens BIGINT,
  upstream_id VARCHAR(100),
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX relay_request_key_month ON relay_request(key_id, charge_month);
CREATE TABLE relay_event (
  id VARCHAR(36) PRIMARY KEY,
  request_id VARCHAR(36) NOT NULL REFERENCES relay_request(id),
  kind VARCHAR(40) NOT NULL,
  cost DECIMAL(24,10) NOT NULL,
  held DECIMAL(24,10) NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
