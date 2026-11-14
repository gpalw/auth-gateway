# Release readiness and operator handoff

Development and local readiness validation only. No merge, workflow dispatch,
production credential access, DNS edit, deployment or paid provider call has been
performed. The ongoing user goal requires all agreed functionality checked,
**merge to main first**, release from that exact main commit, then online acceptance.
A feature branch, mock slice or draft PR does not complete that goal.

## Actual main path today

`.github/workflows/ci-cd.yml` runs on main push (and manual dispatch), tests and
packages the **root identity service**, uploads its JAR, then invokes
`scripts/deploy-systemd.sh` on the VPS. That script replaces the identity JAR,
records `/opt/auth-gateway/releases/<git-sha>` and backup history, restarts
`auth-gateway.service` and rolls back the JAR on failed health. Merging this PR
therefore automatically redeploys identity even though the relay is isolated.
The workflow does **not** build, install or expose api-relay. It is unchanged.

`relay-ci.yml` is a new secret-free test-only workflow for this feature branch and
PR. It runs relay H2/PostgreSQL and root identity checks. It has no deployment job,
SSH secrets, environment or dispatch trigger. Remote CI results must be reviewed
separately from local evidence.

## Gates before a release decision

1. Review the final draft PR, exact SHA, local evidence, CI results and remaining
   compatibility gaps. Confirm the root identity diff is empty and main has not
   advanced incompatibly. The owner/dot decides when to merge, knowing main's
   automatic identity deployment. Do not run deployment workflows just to test.
2. After merge, record the actual resulting main SHA (`git rev-parse origin/main`)
   and identity deployment receipt. Rebase/squash may change the SHA: never deploy
   a pre-merge feature-branch artifact as if it were the main artifact.
3. Check out that immutable main SHA in a fresh build directory and run root and
   relay `clean verify`, the PostgreSQL matrix, packaged Python-fake/browser smoke
   and proxy acceptance. Build the relay JAR from that same SHA and record SHA-256.
   Preserve commit, checks, JAR hash, Java version and configuration price version
   as an immutable release manifest under `/opt/api-relay/releases/<main-sha>`.
4. Approve a separate relay service/database/account, backup restore test,
   persistent pepper handling, model-specific verified prices and limits,
   provider billing alerts and a reconciliation procedure. The loopback/token
   owner interface is implemented; an OIDC-integrated owner UI remains deferred.
5. Supply real credentials only through operator-managed server files/secret store.
   Production requires the exact OpenAI URL, non-mock versioned prices, dedicated
   PostgreSQL database named `api_relay`, separate service account, loopback port
   19091 and unmodified forwarding policy. Protect secret files/backups with 0600
   permissions. Do not show or send secrets in task reports.
6. Review the templates in `deploy/`, complete TLS certificate paths, and validate
   systemd/Nginx on the target OS. No template is installed by this PR. Configure
   `relay.trusted-proxy-ips` to the exact observed Nginx TCP peer; the proxy must
   append the observed public client to X-Forwarded-For. Public access must deny
   `/admin`, `/dashboard*` and `/health`; owner access uses a direct SSH tunnel.
   DNS/TLS and service installation are separate explicit deployment decisions.

## Online acceptance after approval

First verify identity health, issuer/discovery/JWKS, stable `sub`/`user_id`, existing
registered-client Google login and logout/return paths. Preserve identity config,
signing keys, database registrations, Nginx and public homepage during the relay
release. Root health alone is insufficient evidence of OAuth compatibility.

Test the relay release privately against the fake provider on the target host
before configuring real upstream mode. Confirm 24 concurrent over-budget calls,
zero upstream calls on denial, restart-persistent revocation/holds, PostgreSQL
locking, streaming flush/timeout/cancellation through Nginx, trusted-proxy source
checks, and public management denial. Confirm that production mode refuses unsafe
configuration before migrations run. The fake provider override is restricted to
mock mode and must not be used as the production target.

A real provider smoke test incurs money and is **not authorized in this task**.
The owner must separately approve any tightly capped real call and verify usage
against provider billing. Then test the intended third-party client with the
custom base URL, both APIs, text-only restrictions and SSE. Only after online
acceptance and receipts should this ongoing task be marked complete.

## Rollback

Disable relay ingress first, revoke affected gateway credentials if appropriate,
and stop new admissions. Drain/cancel outstanding calls; preserve all RESERVED
and UNRESOLVED liabilities. Never clear holds because a process restarted.
Point `/opt/api-relay/current` back to the previous verified release and restart
only `api-relay.service`. Verify loopback health, key revocation and accounting.
If this is the first release, leave relay ingress closed and stop that service.

Keep additive Flyway migrations and the database/pepper backup; do not roll back
the database destructively or restore an older accounting snapshot over real
charges. A schema-incompatible rollback needs an operator-reviewed forward repair.
Reconcile ambiguous provider charges before releasing holds. Identity is a separate
process: relay rollback must not modify its signing key, credentials, issuer,
registrations, Nginx host or data. If the main-triggered identity deployment itself
fails, use its existing deployment backup/receipt procedure in `docs/ci-cd.md` and
repeat authentication acceptance; do not improvise credential migrations.

## Still unverified / deliberately unsupported

Target-host systemd/TLS/DNS, real public proxy behavior, kill/restart under production
load, production credentials/prices/billing and actual third-party
client interoperability have not been exercised. Local tests use H2 and PostgreSQL
with fake HTTP providers; no provider SDK or paid API is called. A read-only private
usage page and protected management API are supplied, not an OIDC admin integration.
Hosted tools, tool definitions, multimodal inputs, background jobs, stored response
retrieval and generic HTTP routes are rejected and out of the bounded text scope.

Local multi-process acceptance is implemented in `scripts/verify-multiworker.cjs`:
two real JVMs share the disposable PostgreSQL database, exercise 24 concurrent
requests, cross-worker/restart revocation and a hard-kill retained reservation.
This is local evidence; repeat the relevant checks on the approved target host.
