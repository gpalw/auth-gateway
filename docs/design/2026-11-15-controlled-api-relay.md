# Controlled API Relay / Gateway Platform — implementation handoff (2026-11-15)

## Why this exists

The existing `gpalw/auth-gateway` project is a **production identity gateway**: Google login, stable internal identity, OIDC issuer, registered downstream applications, owner-only controls, and a private portal at `auth.liangwendev.com`. Preserve it. The user also wants a **self-hosted API gateway platform** that can issue disposable, OpenAI-compatible client tokens to trusted third-party tools without giving those tools the actual upstream OpenAI key. Each client key needs independent model permissions, request controls, budget limits, revocation, and usage visibility. The relay is a useful Java backend project, but security and correctness matter more than feature count.

**Do not turn the existing OIDC issuer into a generic public request proxy.** Implement the API relay as a separately deployable service/module in this repository (suggested `services/api-relay/`, with its own executable, database schema, service identity, tests, and configuration). Retain the existing identity application and its public contracts unchanged. The relay's future private admin UI can use the existing auth gateway as its OIDC issuer.

## Non-negotiable repository/production constraints

- `main` is wired to GitHub Actions auto-deployment of the **live identity gateway**. Work only on `feat/openai-relay-controlled-keys-20261115` or further feature branches. **Do not merge/push to main, invoke deployment workflows, modify live Nginx/systemd/DNS, rotate or inspect production secrets, or use real OpenAI quota** without explicit user approval.
- Do not change the auth service's Spring Security filter chains, OIDC issuer URL, existing Google callback, signing keys, stable `sub` / `user_id`, platform registrations, login screens, or existing database migrations as part of relay development.
- Existing documented production configuration: `auth.liangwendev.com`, identity process on loopback port 19090, PostgreSQL, private admin path. Run the relay on a **different** loopback port and a separate database/schema. Proposed public base URL **for future manual deployment**: `https://api.liangwendev.com/v1`, not `auth.liangwendev.com`. Actual DNS and deployment must wait for user approval.
- Never commit API keys, generated bearer tokens, passwords, .env files, credentials, or private user prompt/response data. Tests must use a mock upstream, not live chargeable calls.

## Target user journey

1. Owner signs in to a private relay admin area using the existing Auth Gateway OIDC integration (or initially administers through an explicitly documented loopback-only interface; do not expose unauthenticated key-management endpoints).
2. Owner creates a named key, e.g. `ThirdPartyTool`, selects allowed model identifiers, optional trusted source IP/CIDR, expiry, per-minute request limit, monthly USD budget, and optionally total USD budget.
3. UI shows one generated bearer token **once**. Prefer `sk-lw-<keyId>_<cryptographically-random-secret>`, using at least 256 bits of CSPRNG entropy. This is a **gateway-issued token**, NOT an OpenAI token; prefixes should remain distinguishable. Most OpenAI-compatible clients accept a custom base URL and arbitrary bearer token, although some clients impose extra format validation.
4. Third-party tool is configured with:
   - API Base URL: `https://api.liangwendev.com/v1` (after deployment)
   - API Key: `sk-lw-...` (gateway token; never the upstream key)
5. Relay validates the client key and policies, authorizes only known endpoints and allowed models, atomically reserves budget, forwards through a fixed configured upstream OpenAI URL using the server-held provider key, streams/returns the result, records usage and reconciles the reservation.
6. Owner can list keys by **prefix only**, see daily/monthly cost and failure reason summaries, disable/revoke immediately, and rotate key credentials. Revoked/expired/over-budget keys fail before any provider request.

## Architecture

```text
Third-party client
  |  Authorization: Bearer sk-lw-...
  |  base_url = https://api.liangwendev.com/v1
  v
Nginx HTTPS (future/manual) -> API Relay (Java 21 / Spring Boot, loopback 19091)
  | -- verify gateway token + source policy
  | -- endpoint & model allowlist + payload constraints
  | -- token/IP rate limits + concurrency limit
  | -- atomic budget reservation (SQL transaction)
  | -- fixed upstream OpenAI client w/ REAL key in server-only env/secret store
  | -- non-stream JSON or SSE streaming proxy
  | -- usage ledger / audit / reconciliation
  v
https://api.openai.com/v1

Private owner admin (future): existing Auth Gateway OIDC --> relay management API
Persist relay metadata/ledger in separate PostgreSQL schema/database
```

Never let a client select arbitrary upstream URL, host, provider key, callback URL, Authorization value, or arbitrary headers. This is **not** an open HTTP proxy. Do not offer generic unrestricted URL forwarding in this phase. Provider abstraction and generic Spring Cloud Gateway routing are later separate phases.

## Scope and order of implementation

### P0 — Baseline, threat model, and executable skeleton

- Read `README.md`, `pom.xml`, `docs/ci-cd.md`, `docs/design/login-refresh-production-scope.md`, existing security/config tests, and source structure.
- Produce a short decision note explaining the separate-service boundary and confirming no identity contract changes.
- Add isolated Java 21/Spring Boot relay module and local-only configuration; independent PostgreSQL/Flyway schema, no real secrets and no public deployment.
- Provide README with `./mvnw test` (or documented module-specific Maven invocation), isolated runtime port, mock upstream address, and example env variables. Preserve existing auth build/test.
- Config should fail closed for production when provider URL, provider key, administrative protections or known model prices are missing. Never silently fall back to public open access.

### P1 — Functional OpenAI-compatible relay

- Implement exactly `POST /v1/chat/completions`, `POST /v1/responses`, and `GET /v1/models` (models returned only from the permitted local allowlist; no provider-wide leakage).
- Respect standard `Authorization: Bearer ...` input; emit upstream HTTP status and safe JSON error body compatible with OpenAI clients. Do not forward incoming bearer to OpenAI; substitute the server-side provider key. Validate content types, payload size, known model, model-level output ceiling, timeout/cancellation and controlled concurrency.
- Support JSON and actual SSE streaming end-to-end; preserve ordered events and tool-call payload shapes; no buffering of entire streams. Handle disconnects and timeout, including accounting for provider charges when the downstream disconnects.
- For `/v1/chat/completions`, use `stream_options.include_usage` when provider supports it; for `/v1/responses`, parse final `response.completed` usage. Some interrupted streams lack usage: see ledger failure policy below.
- MVP traffic should be **text-only**, without images, audio, file uploads, hosted tools, web search, arbitrary upstream headers or background jobs; validate/reject unsupported parameters with readable errors so the cost model does not pretend to cover unknown charges.
- Configuration must support a **fixed** `https://api.openai.com/v1` upstream in production and localhost test upstreams. Production startup must reject non-HTTPS, non-allowlisted upstream origins. Test-only overrides must not be exposed in production.

### P2 — Keys, policy engine, quotas, ledger

- Create/revoke/rotate gateway tokens; return raw token only on creation/rotation. Store a lookup identifier, token prefix, keyed one-way hash and revocation metadata, **never raw tokens**. Use `SecureRandom` and constant-time comparison; secrets needed for hashing live outside Git.
- Per-key policies: enabled/revoked, expiry, model whitelist, per-minute RPM, max concurrent calls, optional trusted IP CIDRs, per-month USD budget and total USD budget. Do not use `Origin`, `Referer`, CORS, or arbitrary `X-Forwarded-For` as proof of legitimate source. Trust forwarded client IP only from a configured reverse proxy.
- Store immutable financial ledger entries: request id, key id, model, timestamp, outcome, input/output/cache token usage when available, price schedule version, cost USD, reservation, settlement status, upstream request id and error category. **Never log or persist prompts, completion text, raw Authorization values, full bodies, or provider key.**
- Pricing must be versioned/admin-configurable, not silently hardcoded forever. Disallow unknown model/pricing combinations. Use decimal arithmetic and explicit currency; account for cached tokens if the provider returns them. Prevent negative totals and double settlement.
- Budget checks must be concurrency-safe: reserve a **conservative maximum** charge before forwarding; include input estimate, configured output cap, model price, concurrent reservations and existing settled spend. If the reservation cannot be made, reject **before** contacting upstream. Atomic reservation across multiple workers; don't rely on an in-memory synchronized map.
- At completion settle using provider-reported usage and release only unused reservation. When stream fails/disconnects or final usage is unavailable, retain an unresolved reservation/estimated liability and mark for manual or scheduled reconciliation; **do not release it as if it were free**. Document that an exact hard stop to the cent cannot be promised, because some provider-side billing/usage records are asynchronous and not all cost classes can be pre-estimated; keep safety margin / fail closed. Add an administrative reconciliation path and retained audit trail.
- Rate limit with correct HTTP 429, optional `Retry-After`, no unbounded queuing and no arbitrary automatic POST retries. Distinguish gateway quota errors from upstream quota errors.
- Owner-only management interface/API to configure policies and inspect prefix-only keys and aggregated ledger; local-only protected endpoints are okay for first slice, but do not ship a public unauthenticated admin interface. OIDC integration with auth gateway is the preferred later owner-facing UI.

### P3 — Broader self-hosted API gateway capabilities (separate follow-up)

After P1/P2 and threat-model review, evaluate a dedicated API-gateway runtime (e.g. Spring Cloud Gateway) for **allowlisted** service routes, versioned route configuration, gateway-wide rate limiting, upstream health, circuit breakers and observability. Do not expand this into arbitrary user-supplied target URLs or mix traffic-routing authentication with the OIDC issuer.

## Tests and acceptance gates

All first-pass tests must be local, automated, charge-free, using a mock/fake OpenAI upstream and fake upstream key. Include:

- Authorized non-stream chat and responses requests; correct headers/body/usage; models list filtered per key.
- Unauthorized, expired, revoked, wrong model, unknown endpoint, arbitrary upstream URI, malformed JSON, oversized body => reject with no upstream call.
- Concurrent calls (20+), low available budget, repeated request ids/retries => no overspend due to race; no double charge; no negative ledger, no money overflow.
- Streaming chat and responses, SSE event order, provider 429/5xx, mid-stream provider failure, downstream disconnect, usage present/missing, budget unresolved state.
- Rate limit/window expiry, model-specific price schedule versions, timezone/month rollover, key rotation with old key immediately invalid, untrusted spoofed forwarded IP.
- Secrets never appear in app logs, JSON error bodies, management list APIs or Git; input/output content never appears in ledger.
- Existing identity gateway test suite, OAuth/OIDC metadata, stable subjects and registered-client flows unchanged.
- Provide HTTP examples for an OpenAI SDK with custom `base_url` and a fake token, plus a mock demonstration that requires **no real OpenAI credentials**.

## Deliverables from local Codex

1. Inspect worktree and establish clean baseline; do not overwrite unrelated working-tree changes.
2. Implement P0 + one verified vertical slice of P1/P2 first: issue/revoke key -> non-stream `chat/completions` -> quota reserve -> mock upstream -> settle -> ledger. Commit and report tests.
3. Then implement Responses + SSE + full policies and robust fault scenarios, in separate reviewable commits. Avoid long unfinished branches and never skip tests to meet a deadline.
4. Update this design only when decisions differ, explaining why. Add local runbook, environment template and a concise threat model.
5. Return a handoff report: changed files, commit SHAs, commands actually run with outcomes, endpoints tested, remaining risks/known gaps, exact local test URLs. Do **not** deploy to production or call a real chargeable API.
6. Open a PR against `main` **only for review** after tests; never merge it without explicit user approval because a merge/deploy affects the identity production system.

## Explicitly out of scope for this round

- Public signup, selling/re-selling access, arbitrary third-party providers, generic outbound proxy, organization tenancy, OAuth login replacement, production DNS/Nginx/systemd changes, real API spend tests, automatic secret rotation, usage scraping by a third-party client, rewriting existing identity provider, and pretending client-sent `Origin` verifies actual website of origin.
