ALTER TABLE relay_key ADD expires_at VARCHAR(40);
ALTER TABLE relay_key ADD rpm INTEGER NOT NULL DEFAULT 60 CHECK (rpm > 0);
ALTER TABLE relay_key ADD max_concurrent INTEGER NOT NULL DEFAULT 8 CHECK (max_concurrent > 0);
ALTER TABLE relay_key ADD source_cidrs VARCHAR(2000) NOT NULL DEFAULT '';
ALTER TABLE relay_key ADD rate_minute BIGINT NOT NULL DEFAULT 0;
ALTER TABLE relay_key ADD rate_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE relay_request ADD idempotency_hash VARCHAR(64);
ALTER TABLE relay_request ADD CONSTRAINT relay_idempotency UNIQUE(key_id, idempotency_hash);
CREATE TABLE relay_key_event (
  id VARCHAR(36) PRIMARY KEY,
  key_id VARCHAR(36) NOT NULL REFERENCES relay_key(id),
  kind VARCHAR(32) NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
