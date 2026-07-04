# Auth Gateway Production Hardening Design

## Goal

Make the existing auth gateway safe enough for personal production use on the VPS before expanding it into a broader team or public identity platform.

The first hardening stage is about reducing operational and security risk in the live system while preserving the current architecture: auth-gateway remains an identity portal and OpenID Connect issuer, not a business traffic proxy.

## Current Evidence

Current repository state:

- Spring Boot auth gateway already acts as an OIDC issuer for downstream apps.
- Google login, internal gateway user ids, platform registrations, portal links, `/api/me`, `/actuator/health`, CI packaging, systemd deployment, and rollback scripts already exist.
- The public admin path is documented as blocked by Nginx.

Current live VPS evidence from July 2026:

- `auth-gateway.service` is active.
- `http://127.0.0.1:19090/actuator/health` returns `UP`.
- `SESSION_COOKIE_SECURE=true`.
- A persistent JWT signing key is configured at `/etc/auth-gateway/auth-gateway-private-key.pem`.
- Public `https://auth.liangwendev.com/admin/services` returns `404`.
- The live database is still file-based H2: `jdbc:h2:file:/opt/auth-gateway/data/auth-gateway;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`.
- `ALLOWED_EMAILS` and `ALLOWED_DOMAINS` are both empty, so any verified Google account can currently sign in.

## Production Definition For Stage 1

Stage 1 means "personal production": the service is reliable and safe for the owner and explicitly allowed accounts, not yet a multi-admin or public identity product.

The system is stage-1 production ready when:

1. Public production startup fails closed when no Google account allowlist is configured.
2. The live database runs on PostgreSQL, with a documented and rehearsable H2-to-Postgres migration path.
3. Admin pages are protected inside the application, even if Nginx is misconfigured.
4. Operators can identify the currently deployed revision without reading process lists or leaking secrets.
5. A production check script can verify the critical deployment invariants from the VPS.
6. Documentation and example env files match the actual `auth.liangwendev.com` and `tools.liangwendev.com` deployment shape.

## Non-Goals

Stage 1 will not add:

- Team roles and permissions.
- Public self-service user signup.
- MFA.
- Password login.
- Organization tenancy.
- A redesigned portal UI.
- Full SIEM, metrics dashboards, or alerting.

Those belong in later hardening stages after the single-owner production baseline is safe.

## Approach Options

### Option A: Personal Production Hardening

This is the recommended path.

It treats the current VPS as the production environment and fixes the immediate gaps: Google allowlist, PostgreSQL, admin defense in depth, revision visibility, and production checks.

Trade-off: it does not solve multi-admin governance yet. That is acceptable because the current system is owned and operated by one person.

### Option B: Small Team Admin Platform

This adds admin authentication, roles, audit logs, and platform-management permissions now.

Trade-off: it gives a better foundation for shared administration but delays urgent safety fixes such as allowlist enforcement and database migration.

### Option C: Public Identity Product

This adds rate limiting, user lifecycle, account recovery, abuse handling, broader observability, and compliance-minded operations.

Trade-off: it is too broad for the current deployment and would increase complexity before the existing personal-production risks are addressed.

## Recommended Design

Implement Option A as a focused stage-1 hardening release.

### 1. Production Safety Guard

Add a startup validation component that checks the effective production posture.

The guard should classify production mode when either of these is true:

- `AUTH_GATEWAY_ISSUER` starts with `https://` and is not localhost.
- `APP_ENV=production` or `AUTH_GATEWAY_ENV=production` is set.

In production mode, startup must fail if:

- both `ALLOWED_EMAILS` and `ALLOWED_DOMAINS` are empty,
- JWT signing key is not configured,
- `SESSION_COOKIE_SECURE` is false,
- H2 console is enabled,
- issuer is localhost or plain HTTP.

The guard should allow local development defaults to keep working.

This is intentionally application-level protection. It prevents accidental unsafe deploys even if the service file or env file is edited by hand.

### 2. Admin Defense In Depth

The current live deployment blocks `/admin` publicly at Nginx, but the application permits `/admin/**` when `ADMIN_ENABLED=true`.

Stage 1 should add application-level admin protection:

- Public requests to `/admin/**` must not be allowed only because Nginx usually hides them.
- Admin access should require either a loopback request or a configured admin bearer token/header.
- The simplest safe first version is:
  - keep `/admin/**` enabled only for local/SSH-tunnel use,
  - accept requests from `127.0.0.1`, `::1`, or a configured `ADMIN_ALLOWED_PROXY_IPS`,
  - optionally require `ADMIN_ACCESS_TOKEN` when configured.

The admin UI can remain simple. The goal is defense in depth, not a full admin identity system.

### 3. PostgreSQL Migration Path

Stage 1 should migrate live persistence from H2 to PostgreSQL.

The migration path should be scripted and reversible:

1. Stop auth-gateway or put it into maintenance for a short window.
2. Back up `/opt/auth-gateway/data/auth-gateway*`.
3. Export the existing H2 tables for users, external accounts, platform registrations, redirect URIs, and logout redirect URIs.
4. Create the PostgreSQL database and user.
5. Import data into PostgreSQL.
6. Switch `/etc/auth-gateway/auth-gateway.env` to PostgreSQL.
7. Start auth-gateway and verify:
   - health is `UP`,
   - portal loads,
   - existing platform registrations exist,
   - Interview Intelligence login still routes through auth-gateway,
   - JWKS still uses the persistent signing key.

If export/import is too risky to fully automate in one commit, the first implementation can produce a dry-run migration tool and an operator checklist. It must not silently discard registrations or users.

### 4. Revision And Config Summary

Add safe release metadata so an operator can see what is running.

The existing deployment script already writes release directories and `deploy-info.env`. Stage 1 should expose or generate a safe summary with:

- git revision,
- deploy time,
- GitHub run id,
- application version,
- issuer,
- database kind only, such as `h2` or `postgresql`,
- admin enabled status,
- allowlist configured status,
- signing key configured status.

No secrets, raw database URLs with passwords, client secrets, tokens, private keys, or email allowlist contents should be exposed publicly.

The summary can be available through:

- `/actuator/info` for non-sensitive facts, and
- `scripts/prod-check.sh` for full operator checks over SSH.

### 5. Production Check Script

Add `scripts/prod-check.sh` for the VPS.

The script should be safe to run repeatedly and should return non-zero when a critical production invariant fails.

Checks:

- systemd service is active,
- local health endpoint is `UP`,
- public issuer metadata loads,
- JWKS loads,
- public `/admin` returns `404`,
- local `/admin/services` is reachable through loopback when admin is enabled,
- allowlist is configured,
- database is PostgreSQL,
- JWT signing key file exists and is not world-readable,
- session cookie secure is enabled,
- H2 console is disabled,
- Nginx has the `/admin` public block,
- Interview platform client registration points to `tools.liangwendev.com/interview`.

The script must redact secrets and avoid printing `GOOGLE_CLIENT_SECRET`, platform client secrets, private key material, or database passwords.

### 6. Documentation And Env Alignment

Update production docs and examples so they match the current deployment shape:

- `auth.liangwendev.com` for the gateway.
- `tools.liangwendev.com/interview` for Interview Intelligence.
- `tools.liangwendev.com/job` for Job CRM.
- `PORT=19090` and `SERVER_ADDRESS=127.0.0.1` for the current systemd deployment.
- PostgreSQL as the production default.
- H2 explicitly labeled as local-only or temporary.
- Admin exposed only through SSH tunnel and application-level protection.

## Data Model Impact

Stage 1 should avoid unnecessary schema churn.

PostgreSQL migration should preserve existing tables:

- `app_users`
- `external_accounts`
- `platform_registrations`
- `platform_redirect_uris`
- `platform_logout_redirect_uris`

No user-facing identity claim should change. Downstream apps should still use `sub` or `user_id` as the stable gateway user id.

## Error Handling

Production guard failures should be explicit and actionable, for example:

- `Production issuer requires ALLOWED_EMAILS or ALLOWED_DOMAINS`
- `Production issuer requires persistent JWT signing key`
- `Production issuer requires SESSION_COOKIE_SECURE=true`
- `Production issuer cannot use the H2 console`

Production check failures should identify the failing invariant and the suggested fix without printing sensitive values.

## Testing Strategy

Add tests before implementation.

Required test coverage:

- production guard fails with HTTPS issuer and empty allowlist,
- production guard allows localhost development with empty allowlist,
- production guard fails when production mode has no signing key,
- production guard fails when secure cookies are disabled,
- admin protection rejects a non-loopback request when enabled,
- admin protection allows loopback access or token-backed access,
- production summary redacts secrets,
- production check helper parses safe env values without leaking secrets.

Existing OIDC and portal tests should continue to pass.

## Deployment Plan

Stage 1 implementation should be deployed in two phases:

1. Code and docs deploy with production guard in warning-only or explicitly configured compatible mode if needed.
2. VPS env is corrected:
   - set an allowlist,
   - switch to PostgreSQL,
   - confirm signing key and secure cookie settings,
   - confirm admin protection settings,
   - run `scripts/prod-check.sh`.

After phase 2, production guard can remain fail-closed for future deploys.

## Acceptance Criteria

Stage 1 is complete when current evidence proves:

- all tests pass,
- public `/admin` returns `404`,
- application-level `/admin` protection is active,
- production startup would fail without an allowlist,
- live env has an allowlist configured,
- live env uses PostgreSQL,
- live JWT signing key is persistent and permission-restricted,
- `scripts/prod-check.sh` exits `0` on the VPS,
- Interview Intelligence login still reaches Google through auth-gateway,
- README and env examples match the live URL shape,
- no secrets are committed or printed by checks.
