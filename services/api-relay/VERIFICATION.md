# Local verification — 2026-11-14 UTC

Machine: DESKTOP-WEN, Windows, Java 21.0.11. Work was done in an isolated clone
under `D:\Codex\2026-11-15\task-2\auth-gateway`, following the existing remote
`feat/openai-relay-controlled-keys-20261115`. Both original checkouts were inspected
first; their uncommitted identity/portal work was preserved. No applicable
AGENTS.md or checkout `.agents/skills` was present. The issue, complete relay
design, README, POM, deployment docs and existing regression code were read.

## Commands actually executed

| Check | Result |
| --- | --- |
| `mvnw.cmd -B test` before changes | 86 tests, 0 failures/errors, 2 environment skips |
| `mvnw.cmd -B -f services/api-relay/pom.xml clean verify` | 28 tests, 0 failures/errors/skips; executable JAR packaged |
| Same relay verification with `RELAY_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55439/relay_test` | 28 tests, 0 failures/errors/skips, actual PostgreSQL 17.11; all four Flyway migrations |
| `node services/api-relay/scripts/verify-dashboard.cjs` | 8 checks pass: packaged chat, packaged Responses SSE, private accounting, 1280/390/320px layouts, no browser storage/external requests, revocation |
| `node services/api-relay/scripts/verify-multiworker.cjs` | 3 checks pass: two real JVMs, cross-process/restart revocation, hard-kill retained hold |
| `mvnw.cmd -B clean verify` with local Nginx/Chromium and mock Google fixture | 86 tests, 0 failures/errors, 1 Bash-discovery skip; 85 executed tests pass; identity JAR packaged |
| Existing real Spring + Nginx + Chromium identity scenario | 28 recorded checks pass, including login/PKCE/state/nonce, stable session/identity, private navigation, return targets and logout |
| Explicit Git Bash `-n scripts/prod-check.sh` and `-n scripts/deploy-systemd.sh` | Both pass, supplementing the JUnit Bash-discovery skip |
| Python fixture compile, Node script syntax checks, `git diff --check` | Pass |

Docker Desktop was unavailable. Instead, checksum-verified Zonky PostgreSQL
17.11 Windows test binaries from Maven Central ran a disposable workspace-local
cluster bound only to loopback. No operational database or credential was used.

In the two-JVM run, 24 simultaneous requests yielded **2 accepted / 22 rejected**,
with at most two concurrent upstream calls. Only accepted requests reached the
fake provider. Revocation applied to the other JVM and survived restart. Killing
a JVM after dispatch left a durable RESERVED charge; restart still denied the
next request before upstream. Unit/integration tests also exercise SQL locking
from separate worker objects, settlement idempotency, UTC month rollover,
immutable price versions, invalid usage, manual and stale-hold reconciliation.

Streaming checks prove that the first frame arrives before provider completion,
preserve ordered nested tool-call deltas, settle chat/Responses final usage and
retain holds on missing usage, provider errors, timeout or downstream disconnect.
Secrets and fixture prompt/output content are absent from logs, key lists and
ledger tables. Payload/type/endpoint/model/source/expiry/quota denials do not reach
the provider. Strict JSON and unsafe-config-before-migration regressions pass.

Tested relay JAR SHA-256:
`e65beb1e04b495b07fdb1b671b5eb4a0603ac2ac026e0f44435da6ff1fad7f6f`.
This is a **development** artifact, not a release from main.

Local evidence is outside committed source: workspace `relay-final-h2.log`,
`relay-final-postgres.log`, `identity-final.log`, `validation/relay-h2-reports/`,
`validation/identity-e2e/`, and module `target/browser/` / `target/multiworker/`.
Browser screenshots were visually inspected at desktop and 320px widths.

## Scope and remaining gates

Implemented: isolated executable/config/database; safe create/revoke/rotate;
protected policy management; both bounded text APIs; filtered models; SSE;
conservative reservations, settlement and manual reconciliation; USD/month/lifetime
budgets; expiry, SQL-backed RPM/concurrency, numeric source CIDRs/trusted proxies;
safe accounting categories and immutable prices; private read-only usage view.

The root identity `src`, POM, issuer/configuration, existing deployment workflow
and production deployment files have no diff. The new workflow only runs checks.
No main push/merge, deployment dispatch, live DNS/Nginx/systemd edit, production
credential access or paid OpenAI call occurred.

Remote CI results are separate evidence and must be checked on the pushed SHA.
Actual target-host proxy/TLS/systemd, production prices/billing, real third-party
SDK/client interoperability and online authentication acceptance remain unverified.
OIDC integration of the owner UI remains deferred; the approved initial interface
is direct loopback plus a separate owner bearer. Unsupported hosted tools,
multimodal input, background jobs and stored-response retrieval fail closed.
See `RELEASE.md` for merge-main-first release provenance, prerequisites and rollback.
The user's ongoing main-release-and-online-acceptance goal is not complete.
