# H2 To PostgreSQL Migration

This migration moves auth-gateway production persistence from the temporary H2 file database to PostgreSQL.

## Tables To Preserve

- `app_users`
- `external_accounts`
- `platform_registrations`
- `platform_redirect_uris`
- `platform_logout_redirect_uris`

The gateway user ids must not change. Downstream apps use `sub` and `user_id`, so preserving `app_users.id` is required.

## Maintenance Window

1. Announce a short login maintenance window.
2. Stop auth-gateway with `sudo systemctl stop auth-gateway.service`.
3. Back up `/opt/auth-gateway/data/auth-gateway*`.
4. Export H2 SQL.
5. Create PostgreSQL database and user.
6. Import exported data.
7. Update `/etc/auth-gateway/auth-gateway.env`.
8. Start auth-gateway.
9. Run `scripts/prod-check.sh`.

## Dry Run

Back up the current H2 files without exporting SQL:

```bash
sudo H2_DATABASE_PATH=/opt/auth-gateway/data/auth-gateway \
  ./scripts/h2-to-postgres-dry-run.sh
```

Export SQL as well when an H2 jar is available:

```bash
sudo H2_DATABASE_PATH=/opt/auth-gateway/data/auth-gateway \
  H2_JAR=/path/to/h2.jar \
  ./scripts/h2-to-postgres-dry-run.sh
```

Review the exported SQL before importing. Do not run blind import commands on production data.

## PostgreSQL Setup

```bash
sudo -u postgres psql <<'SQL'
CREATE USER auth_gateway WITH PASSWORD 'replace-with-strong-password';
CREATE DATABASE auth_gateway OWNER auth_gateway;
SQL
```

## Env Switch

Set these values in `/etc/auth-gateway/auth-gateway.env`:

```text
DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/auth_gateway
DATABASE_USERNAME=auth_gateway
DATABASE_PASSWORD=replace-with-strong-password
JPA_DDL_AUTO=update
H2_CONSOLE_ENABLED=false
```

Keep the existing issuer, Google OAuth settings, platform client secrets, allowlist, and JWT signing key settings unchanged.

## Verification

```bash
sudo systemctl start auth-gateway.service
curl -fsS http://127.0.0.1:19090/actuator/health
curl -fsS https://auth.liangwendev.com/.well-known/openid-configuration
curl -fsS https://auth.liangwendev.com/oauth2/jwks
sudo ./scripts/prod-check.sh
```

Expected results:

- health returns `{"status":"UP"}`,
- OIDC discovery returns issuer `https://auth.liangwendev.com`,
- JWKS contains at least one key,
- production check reports PostgreSQL,
- portal platform registrations still exist.

## Rollback

If startup fails after the env switch:

1. Stop auth-gateway.
2. Restore the previous H2 env values.
3. Confirm the H2 files still exist in the backup directory.
4. Start auth-gateway.
5. Check local health.

Do not delete the H2 backup until the PostgreSQL-backed service has run successfully and downstream logins have been verified.
