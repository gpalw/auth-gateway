#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="${ENV_FILE:-/etc/auth-gateway/auth-gateway.env}"
SERVICE_NAME="${SERVICE_NAME:-auth-gateway.service}"
NGINX_CONFIG="${NGINX_CONFIG:-/etc/nginx/sites-enabled/auth.liangwendev.com.conf}"

failures=0

fail() {
  failures=$((failures + 1))
  printf 'FAIL %s\n' "$1"
}

pass() {
  printf 'PASS %s\n' "$1"
}

read_env_value() {
  local key="$1"
  if [ ! -f "$ENV_FILE" ]; then
    return 0
  fi
  awk -F= -v key="$key" '
    $1 == key {
      value = substr($0, length(key) + 2)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      gsub(/^"|"$/, "", value)
      gsub(/^'\''|'\''$/, "", value)
      print value
    }
  ' "$ENV_FILE" | tail -n 1
}

check_systemd() {
  if systemctl is-active --quiet "$SERVICE_NAME"; then
    pass "systemd service is active"
  else
    fail "systemd service is not active: $SERVICE_NAME"
  fi
}

check_local_health() {
  local address port url body
  address="$(read_env_value SERVER_ADDRESS)"
  port="$(read_env_value PORT)"
  address="${address:-127.0.0.1}"
  port="${port:-8080}"
  url="http://$address:$port/actuator/health"
  body="$(curl -fsS --max-time 5 "$url" 2>/dev/null || true)"
  if printf '%s' "$body" | grep -q '"status":"UP"'; then
    pass "local health is UP"
  else
    fail "local health is not UP at $url"
  fi
}

check_public_oidc() {
  local issuer metadata jwks admin_status
  issuer="$(read_env_value AUTH_GATEWAY_ISSUER)"
  if [ -z "$issuer" ]; then
    fail "AUTH_GATEWAY_ISSUER is empty"
    return
  fi
  metadata="$(curl -fsS --max-time 8 "$issuer/.well-known/openid-configuration" 2>/dev/null || true)"
  jwks="$(curl -fsS --max-time 8 "$issuer/oauth2/jwks" 2>/dev/null || true)"
  admin_status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 "$issuer/admin/services" 2>/dev/null || true)"
  if printf '%s' "$metadata" | grep -q '"issuer"'; then
    pass "public OIDC metadata loads"
  else
    fail "public OIDC metadata does not load"
  fi
  if printf '%s' "$jwks" | grep -q '"keys"'; then
    pass "public JWKS loads"
  else
    fail "public JWKS does not load"
  fi
  if [ "$admin_status" = "404" ]; then
    pass "public admin path returns 404"
  else
    fail "public admin path returned $admin_status instead of 404"
  fi
}

check_env_invariants() {
  local allowed_emails allowed_domains database_url secure_cookie h2_console key_path
  allowed_emails="$(read_env_value ALLOWED_EMAILS)"
  allowed_domains="$(read_env_value ALLOWED_DOMAINS)"
  database_url="$(read_env_value DATABASE_URL)"
  secure_cookie="$(read_env_value SESSION_COOKIE_SECURE)"
  h2_console="$(read_env_value H2_CONSOLE_ENABLED)"
  key_path="$(read_env_value AUTH_GATEWAY_JWT_PRIVATE_KEY_PATH)"

  if [ -n "$allowed_emails" ] || [ -n "$allowed_domains" ]; then
    pass "Google allowlist is configured"
  else
    fail "Google allowlist is empty"
  fi

  if printf '%s' "$database_url" | grep -q '^jdbc:postgresql:'; then
    pass "database is PostgreSQL"
  else
    fail "database is not PostgreSQL"
  fi

  if [ "$secure_cookie" = "true" ]; then
    pass "secure session cookie is enabled"
  else
    fail "SESSION_COOKIE_SECURE is not true"
  fi

  if [ "$h2_console" = "false" ] || [ -z "$h2_console" ]; then
    pass "H2 console is disabled"
  else
    fail "H2 console is enabled"
  fi

  if [ -n "$key_path" ] && [ -f "$key_path" ]; then
    local mode
    mode="$(stat -c '%a' "$key_path" 2>/dev/null || true)"
    if [ "$mode" = "600" ] || [ "$mode" = "400" ]; then
      pass "JWT signing key file exists with restricted permissions"
    else
      fail "JWT signing key permissions are $mode"
    fi
  else
    fail "JWT signing key file is missing"
  fi
}

check_nginx_admin_block() {
  if [ -f "$NGINX_CONFIG" ] && grep -q 'location \^~ /admin' "$NGINX_CONFIG" && grep -q 'return 404' "$NGINX_CONFIG"; then
    pass "Nginx blocks public admin path"
  else
    fail "Nginx admin 404 block was not found"
  fi
}

check_platform_registration_hint() {
  local interview_url job_url
  interview_url="$(read_env_value INTERVIEW_URL)"
  job_url="$(read_env_value JOB_CRM_URL)"
  if printf '%s' "$interview_url" | grep -q 'tools.liangwendev.com/interview'; then
    pass "Interview launch URL uses tools.liangwendev.com/interview"
  else
    fail "Interview launch URL is not tools.liangwendev.com/interview"
  fi
  if printf '%s' "$job_url" | grep -q 'tools.liangwendev.com/job'; then
    pass "Job CRM launch URL uses tools.liangwendev.com/job"
  else
    fail "Job CRM launch URL is not tools.liangwendev.com/job"
  fi
}

if [ ! -f "$ENV_FILE" ]; then
  fail "env file is missing: $ENV_FILE"
else
  pass "env file exists"
fi

check_systemd
check_local_health
check_public_oidc
check_env_invariants
check_nginx_admin_block
check_platform_registration_hint

if [ "$failures" -eq 0 ]; then
  printf 'Production check passed\n'
  exit 0
fi

printf 'Production check failed with %s issue(s)\n' "$failures"
exit 1
