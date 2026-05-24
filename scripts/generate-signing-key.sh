#!/usr/bin/env bash
set -euo pipefail

target="${1:-secrets/auth-gateway-private-key.pem}"
mkdir -p "$(dirname "$target")"

if [ -e "$target" ]; then
  echo "Refusing to overwrite existing key: $target" >&2
  exit 1
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$target"
chmod 600 "$target"
echo "Created JWT signing key at $target"
