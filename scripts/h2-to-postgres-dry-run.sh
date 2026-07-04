#!/usr/bin/env bash
set -euo pipefail

H2_DATABASE_PATH="${H2_DATABASE_PATH:-/opt/auth-gateway/data/auth-gateway}"
BACKUP_DIR="${BACKUP_DIR:-/opt/auth-gateway/backups/h2-to-postgres-$(date -u +%Y%m%dT%H%M%SZ)}"
H2_JAR="${H2_JAR:-}"

echo "H2 database path: $H2_DATABASE_PATH"
echo "Backup directory: $BACKUP_DIR"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo so the auth-gateway data files can be backed up." >&2
  exit 2
fi

if ! ls "$H2_DATABASE_PATH"* >/dev/null 2>&1; then
  echo "No H2 files found for $H2_DATABASE_PATH" >&2
  exit 2
fi

install -d -m 0700 "$BACKUP_DIR"
cp -a "$H2_DATABASE_PATH"* "$BACKUP_DIR/"
echo "Backed up H2 files to $BACKUP_DIR"

if [ -z "$H2_JAR" ]; then
  echo "Set H2_JAR to an H2 jar path to export SQL with org.h2.tools.Script."
  echo "Dry-run stopped after backup."
  exit 0
fi

if [ ! -f "$H2_JAR" ]; then
  echo "H2_JAR does not exist: $H2_JAR" >&2
  exit 2
fi

java -cp "$H2_JAR" org.h2.tools.Script \
  -url "jdbc:h2:file:$H2_DATABASE_PATH;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" \
  -user sa \
  -script "$BACKUP_DIR/auth-gateway-h2-export.sql"

echo "Exported H2 SQL to $BACKUP_DIR/auth-gateway-h2-export.sql"
echo "Review the SQL before importing it into PostgreSQL."
