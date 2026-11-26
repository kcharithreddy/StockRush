#!/usr/bin/env bash
# Prints an access token for one of the demo users (buyer1, buyer2, shopadmin) from the local Keycloak.
# Uses the development-only stockrush-cli client (password grant) and DEMO_USER_PASSWORD from .env.
#   TOKEN=$(scripts/token.sh buyer1)
#   curl --cacert infra/tls/ca.crt -X POST -H "Authorization: Bearer $TOKEN" https://localhost:8443/sales/1/buy
set -euo pipefail

user="${1:?usage: scripts/token.sh <buyer1|buyer2|shopadmin>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
password="${DEMO_USER_PASSWORD:-$(grep -E '^DEMO_USER_PASSWORD=' "$root/.env" | cut -d= -f2-)}"
keycloak="${KEYCLOAK_URL:-http://localhost:8180}"

response="$(curl -sS -X POST "$keycloak/realms/stockrush/protocol/openid-connect/token" \
  --data-urlencode grant_type=password \
  --data-urlencode client_id=stockrush-cli \
  --data-urlencode "username=$user" \
  --data-urlencode "password=$password")"

token="$(printf '%s' "$response" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
if [ -z "$token" ]; then
  echo "Could not get a token for $user: $response" >&2
  exit 1
fi
printf '%s\n' "$token"
