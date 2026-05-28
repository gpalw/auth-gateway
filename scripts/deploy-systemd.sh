#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "deploy-systemd.sh must run as root. Use sudo." >&2
  exit 2
fi

JAR_SOURCE="${1:?Usage: deploy-systemd.sh <jar-path> [revision] [run-id]}"
REVISION="${2:-manual-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_ID="${3:-manual}"

APP_NAME="${APP_NAME:-auth-gateway}"
SERVICE_NAME="${SERVICE_NAME:-auth-gateway.service}"
INSTALL_DIR="${INSTALL_DIR:-/opt/auth-gateway}"
ENV_FILE="${ENV_FILE:-/etc/auth-gateway/auth-gateway.env}"
CURRENT_JAR="${CURRENT_JAR:-$INSTALL_DIR/auth-gateway.jar}"
RELEASES_DIR="${RELEASES_DIR:-$INSTALL_DIR/releases}"
BACKUPS_DIR="${BACKUPS_DIR:-$INSTALL_DIR/backups}"
HEALTH_RETRIES="${HEALTH_RETRIES:-30}"
HEALTH_SLEEP_SECONDS="${HEALTH_SLEEP_SECONDS:-2}"

sanitize_revision() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '-'
}

read_env_value() {
  local key="$1"
  local file="$2"
  if [ ! -f "$file" ]; then
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
  ' "$file" | tail -n 1
}

wait_for_health() {
  local health_url="$1"
  local body
  for attempt in $(seq 1 "$HEALTH_RETRIES"); do
    body="$(curl -fsS --max-time 5 "$health_url" 2>/dev/null || true)"
    if printf '%s' "$body" | grep -q '"status":"UP"'; then
      echo "Health check passed on attempt $attempt: $health_url"
      return 0
    fi
    sleep "$HEALTH_SLEEP_SECONDS"
  done
  return 1
}

if [ ! -f "$JAR_SOURCE" ]; then
  echo "Jar not found: $JAR_SOURCE" >&2
  exit 2
fi

if ! systemctl list-unit-files "$SERVICE_NAME" >/dev/null 2>&1; then
  echo "Systemd service not found: $SERVICE_NAME" >&2
  exit 2
fi

REVISION="$(sanitize_revision "$REVISION")"
DEPLOYED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
RELEASE_DIR="$RELEASES_DIR/$REVISION"
BACKUP_DIR="$BACKUPS_DIR/$DEPLOYED_AT"

server_address="$(read_env_value SERVER_ADDRESS "$ENV_FILE")"
server_port="$(read_env_value PORT "$ENV_FILE")"
server_address="${server_address:-127.0.0.1}"
server_port="${server_port:-8080}"
HEALTH_URL="${HEALTH_URL:-http://$server_address:$server_port/actuator/health}"

echo "Deploying $APP_NAME revision $REVISION"
echo "Service: $SERVICE_NAME"
echo "Health URL: $HEALTH_URL"

install -d -m 0755 "$INSTALL_DIR" "$RELEASES_DIR" "$BACKUPS_DIR"
install -d -m 0755 "$RELEASE_DIR" "$BACKUP_DIR"
install -m 0644 "$JAR_SOURCE" "$RELEASE_DIR/auth-gateway.jar"

cat > "$RELEASE_DIR/deploy-info.env" <<EOF
APP_NAME=$APP_NAME
REVISION=$REVISION
GITHUB_RUN_ID=$RUN_ID
DEPLOYED_AT=$DEPLOYED_AT
SERVICE_NAME=$SERVICE_NAME
HEALTH_URL=$HEALTH_URL
EOF

if [ -f "$CURRENT_JAR" ]; then
  cp -a "$CURRENT_JAR" "$BACKUP_DIR/auth-gateway.jar"
fi

tmp_jar="$CURRENT_JAR.tmp.$$"
install -m 0644 "$RELEASE_DIR/auth-gateway.jar" "$tmp_jar"
mv -f "$tmp_jar" "$CURRENT_JAR"

systemctl restart "$SERVICE_NAME"

if wait_for_health "$HEALTH_URL"; then
  ln -sfn "$RELEASE_DIR" "$INSTALL_DIR/current-release"
  ls -1dt "$RELEASES_DIR"/*/ 2>/dev/null | tail -n +6 | xargs -r rm -rf
  echo "Deployment succeeded: $REVISION"
  systemctl --no-pager --lines=20 status "$SERVICE_NAME"
  exit 0
fi

echo "Deployment failed health check. Recent logs:" >&2
journalctl -u "$SERVICE_NAME" --no-pager -n 80 >&2 || true

if [ -f "$BACKUP_DIR/auth-gateway.jar" ]; then
  echo "Rolling back to previous jar from $BACKUP_DIR" >&2
  install -m 0644 "$BACKUP_DIR/auth-gateway.jar" "$tmp_jar"
  mv -f "$tmp_jar" "$CURRENT_JAR"
  systemctl restart "$SERVICE_NAME"
  if wait_for_health "$HEALTH_URL"; then
    echo "Rollback succeeded." >&2
  else
    echo "Rollback did not pass health check. Manual intervention required." >&2
    journalctl -u "$SERVICE_NAME" --no-pager -n 80 >&2 || true
  fi
else
  echo "No previous jar backup was available for rollback." >&2
fi

exit 1
