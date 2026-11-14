CREATE TABLE relay_price (
  model VARCHAR(100) NOT NULL,
  version VARCHAR(80) NOT NULL,
  input_per_million DECIMAL(24,10) NOT NULL,
  cached_per_million DECIMAL(24,10) NOT NULL,
  output_per_million DECIMAL(24,10) NOT NULL,
  max_input INTEGER NOT NULL,
  max_output INTEGER NOT NULL,
  PRIMARY KEY (model, version)
);
