# Controlled API relay

Independent Java 21 / Spring Boot executable. The root identity POM, OIDC code,
issuer, registrations and production deployment remain unchanged. This POM is
intentionally **not** a root Maven module. Nothing deploys this service today.

## Charge-free local run

From the repository root (Java 21 and Python 3):

```powershell
python services/api-relay/scripts/fake_upstream.py
# In a second terminal, generate independent local-only secrets; retain the pepper
# securely when reusing a persistent database. Never print/commit the generated values.
$env:RELAY_HASH_PEPPER = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:RELAY_ADMIN_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
.\mvnw.cmd -f services/api-relay/pom.xml spring-boot:run
```

Relay: `http://127.0.0.1:19091`, fake provider: `http://127.0.0.1:19092/v1`.
Health: `GET /health`. Default model `mock-text` uses synthetic prices only.
Database: relay-local `./data/api-relay` relative to the module working directory.
No Google credentials or real provider key are needed. Runtime configuration must
keep loopback binding and forwarding disabled. PostgreSQL is required in production.

Create a gateway key in the terminal that retains the admin token:

```powershell
$headers = @{ Authorization = "Bearer $env:RELAY_ADMIN_TOKEN" }
$policy = '{"name":"Local tool","models":["mock-text"],"monthly_usd":1,"total_usd":2}'
$key = Invoke-RestMethod http://127.0.0.1:19091/admin/keys -Method Post -Headers $headers -ContentType application/json -Body $policy
# $key.token is returned once; store in the client's secret store. Do not print it.
$clientHeaders = @{ Authorization = "Bearer $($key.token)" }
$chat = '{"model":"mock-text","messages":[{"role":"user","content":"Hello"}],"max_completion_tokens":100}'
Invoke-RestMethod http://127.0.0.1:19091/v1/chat/completions -Method Post -Headers $clientHeaders -ContentType application/json -Body $chat
Invoke-RestMethod http://127.0.0.1:19091/admin/usage -Headers $headers
Invoke-RestMethod "http://127.0.0.1:19091/admin/keys/$($key.id)/revoke" -Method Post -Headers $headers
```

OpenAI Python SDK example (install the SDK yourself, use only the local fake):

```python
import os
from openai import OpenAI
client = OpenAI(base_url="http://127.0.0.1:19091/v1", api_key=os.environ["LOCAL_GATEWAY_TOKEN"], max_retries=0)
reply = client.chat.completions.create(model="mock-text", messages=[{"role":"user","content":"Hello"}], max_completion_tokens=100)
```

## Verification

```powershell
.\mvnw.cmd -B -f services/api-relay/pom.xml clean verify
.\mvnw.cmd -B test  # existing identity regression
```

Tests use only a literal loopback HTTP fake provider with a fake credential.
They check 24 simultaneous calls against a low budget, correct provider credential
substitution, safe audit data, invalid request rejection, revocation, failed and
missing usage, management protection and double-settlement prevention.

For a disposable PostgreSQL fixture, create database/user `relay_test`, bind it to
literal loopback on a test-only port, then set `RELAY_TEST_POSTGRES_URL` to
`jdbc:postgresql://127.0.0.1:<port>/relay_test` and optional
`RELAY_TEST_POSTGRES_PASSWORD`. Run the same Maven command. The test URL validator
rejects other hosts/database names because tests delete fixture rows. Never point
the suite at an operational database. Unset these variables for the H2 run.

The packaged JAR plus Python-fake/Chromium check is:

```powershell
# Requires an installed playwright-core and Chromium; optional environment paths:
# PLAYWRIGHT_PACKAGE, CHROME_PATH, JAVA_HOME, PYTHON.
node services/api-relay/scripts/verify-dashboard.cjs
```

Reports/screenshots are placed in `services/api-relay/target/browser/`. The check
creates only ephemeral local credentials and closes both fixture processes.

## Accounting decisions

Keys use 256-bit random secrets and HMAC-SHA256 with an independent persistent
pepper. Only the lookup UUID, prefix and keyed digest are stored. Key metadata
lists never return the digest or raw token. Management requires both a loopback
transport peer and a separate bearer; forwarding headers fail closed.

All financial operations lock the key row with `SELECT ... FOR UPDATE` in a SQL
transaction. No Java monitor or process-local counter protects the budget. Before
forwarding, reserve the full configured model input ceiling plus requested output
ceiling at uncached prices. Text bytes plus framing allowance must fit that input
ceiling. This is intentionally conservative and can reject affordable requests.
Model schedules and limits are administrator-configured and versioned in YAML;
example prices are not current provider prices. Unknown parameters, modalities,
tools, models, targets, compressed bodies and query strings fail closed.

Each request has a server-generated ID. Append-only financial events accompany
the request projection. Success settles once using nonnegative bounded usage,
including cached input. Missing/invalid usage, network errors, redirects and 5xx
retain the full liability as UNRESOLVED. Explicit provider 4xx rejections release
the hold. No POST retry is performed. Request/response bodies, bearer values and
provider errors never enter the ledger or application logs. API errors use fixed
codes. The body returned to the client is naturally provider content.

UTC month is assigned at reservation; an in-flight call settles in that same
month. Historic held liabilities continue to count against the lifetime budget.
An exact provider-billed hard stop cannot be guaranteed: provider templates,
pricing revisions and asynchronous charges require verified schedules and a
safety margin. Keep ambiguous reservations held pending reconciliation.

## Remaining validation

Production proxy behavior, actual pricing, OIDC owner UI and the deployment path
still require additional validation. Do not expose this work publicly or claim
the overall task complete.

## Supported API subset and streaming

`POST /v1/chat/completions`, `POST /v1/responses`, `GET /v1/models` are supported.
Models are built from the intersection of each key's model policy and locally
priced models; no provider call occurs. Chat accepts text messages, output cap,
temperature/top_p, n=1 and stream options. Responses accepts string input or text
message arrays, optional instructions, output cap, temperature/top_p and streaming.
Both force `store=false`; Responses retrieval/background/conversations, hosted
tools, tool definitions, images/audio/files and arbitrary extra options fail closed.
This deliberately covers a bounded text subset, not every OpenAI SDK feature.

Chat streaming forces `stream_options.include_usage=true`. The relay validates and
flushes individual SSE frames in order (including nested tool-call delta shapes),
buffers at most 64 KiB per event and enforces the configured total response ceiling.
Responses settles on `response.completed`; chat settles after usage plus `[DONE]`.
Disconnects close the provider stream; missing usage, timeout, incomplete/failed
events and malformed streams retain the full hold. Error events contain only
fixed relay codes. There are no automatic upstream POST retries. Nginx buffering
and idle timeouts must be verified before public rollout.

Prices are configured under `relay.prices` and must match the exact model identifier
returned by the provider (use pinned model versions). Production startup rejects
mock model schedules, absent credentials, non-OpenAI targets, forwarded-address
rewriting, non-loopback binding and an identity/shared database configuration.
The provider API field references used are the official [Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
and [Responses](https://developers.openai.com/api/reference/resources/responses/methods/create) references.

Price schedules are persisted in `relay_price`. A model/version pair is immutable:
changing its rates/ceilings fails startup; register a new version instead. Old
schedules remain available for audit. JSON parsing rejects duplicate fields,
trailing documents, overlong numbers and excessive nesting. Accounting stores
safe error categories, never raw provider error messages.

## Private usage view and release handoff

Open `http://127.0.0.1:19091/dashboard` directly or through an SSH tunnel. The shell
contains no private data. Supply the owner token to load prefix-only keys, daily
usage, estimated settled costs, held liabilities and recent error categories.
The token is cleared from the input after loading and never saved in local/session
storage. Clear view removes the displayed data. All data APIs still require the
owner bearer and original loopback transport; the public proxy must block both
management APIs and the dashboard. Key mutation remains in the protected API.

See [RELEASE.md](RELEASE.md) for the actual main-triggered identity deployment,
the separate relay release path from the resulting main SHA, target-host gates,
online authentication acceptance and rollback. `deploy/` contains review-only
templates; no service or DNS changes are applied. Main merge and online acceptance
remain outside this development handoff.

## Policy and accounting administration

Creation accepts `expires_at` (UTC ISO instant), `rpm` (1–6000),
`max_concurrent` (1–64) and `source_cidrs` (numeric IPv4/IPv6 networks).
Defaults are 60 RPM, eight concurrent calls, no expiry or source restriction.
`PUT /admin/keys/{id}/policy` replaces the full policy and budgets; include all
desired values. Reducing a budget below existing liability blocks new calls.
`POST /admin/keys/{id}/rotate` returns the replacement token once; old credentials
immediately fail new admission. Existing dispatched calls can still finish.

RPM uses a fixed UTC minute and counts admitted POSTs, including provider failures.
Concurrency counts RESERVED requests. All counters use the same database key-row
lock as budgets, including across independent workers. Rate admission has no queue.
`Idempotency-Key` (1–128 ASCII identifier characters) is optional: reuse for that
key returns 409 before dispatch, even after settlement or rotation. Responses are
not cached. Without this header, each client retry is a new billable request.

Client addresses come from the original TCP peer. A configured
`relay.trusted-proxy-ips` literal address may supply one X-Forwarded-For header;
the **rightmost** address is used, and the proxy must append its observed peer.
Untrusted forwarding and RFC Forwarded are rejected. Admin always rejects all
forwarding headers. Leave the trust list empty for direct local use. The future
public proxy must deny `/admin/` independently and preserve this transport contract.

`GET /admin/usage` returns prefix-safe recent requests (last 200), UTC daily and
monthly costs/held liabilities, token counts and outcome counts. No prompts or
outputs are stored. `POST /admin/requests/{id}/reconcile` accepts
`{"verified_cost_usd":0.00002,"reason":"provider_usage_verified"}` or zero cost
with `provider_no_charge_verified`. The operator must actually verify provider
billing first. Only UNRESOLVED entries can be reconciled; stale RESERVED entries
older than three provider timeout periods plus 60 seconds can be recovered through
the same operation. Original holds/events are retained; repeat reconciliation is
409. A verified cost above the reservation is recorded in full and blocks further
admission when over budget. Never infer no charge just from a timeout or crash.
