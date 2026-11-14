# Relay boundary and threats

The existing identity server is outside this executable and database. A gateway
token authorizes a narrow text-generation capability; it never grants OIDC/admin
rights. A separate loopback/token management boundary can create and revoke it.

* Stolen client token: per-key model and monetary limits, prompt revocation and
  expiry/rate/source policies limit damage. Existing provider calls
  cannot be recalled by revoking a key.
* Credential disclosure: 256-bit tokens, HMAC at rest, no body/header logging,
  fixed safe errors and prefix-only metadata. Secure backups/pepper storage are
  operator responsibilities; losing the pepper makes existing keys unusable.
* SSRF or open proxy: fixed production URI and paths, no redirects, header
  substitution and strict text payload allowlist. Mock mode accepts only literal
  loopback HTTP. Production mode requires explicit non-mock model pricing and
  separate PostgreSQL database/service account.
* Budget races: database row locks serialize admission and settlement across
  workers. All holds count before dispatch. Unknown charge outcomes retain holds;
  immutable financial events support reconciliation. Provider bills remain the
  ultimate authority and require an operational reconciliation process.
* Source spoofing: no framework address rewriting; admin rejects forwarding
  headers. Explicitly trusted transport peers may supply the appended rightmost
  client address. Target-host proxy behavior still needs acceptance; never use
  Origin/Referer as proof of source.
* Resource exhaustion: bounded input/output, provider timeout, per-event/total
  stream ceilings, bounded server threads, SQL-backed RPM/concurrency. There is no
  arbitrary POST retry or unbounded admission queue.
* Database compromise: service account must be restricted to the dedicated relay
  database; application has no production identity credentials. A database writer
  can tamper with accounting, so protected backups and restricted SQL access are
  required. Append-only events are an application invariant, not tamper evidence.
* Unsafe startup: loopback/transport/database/profile checks precede Flyway
  connection attempts. Price schedule model/version pairs are immutable. A crash
  leaves durable RESERVED liability; reconciliation requires verified billing.
