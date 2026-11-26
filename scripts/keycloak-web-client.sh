#!/usr/bin/env bash
# Points the stockrush-web client of an EXISTING Keycloak at the shop page (https://localhost:8443).
#
# Keycloak imports infra/keycloak/stockrush-realm.json only when the realm does not exist yet, so a
# Keycloak created before the shop page was added still has the old redirect URLs. Run this once:
#   scripts/keycloak-web-client.sh
# Safe to run again. Needs the stack running and KEYCLOAK_ADMIN_PASSWORD in .env.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
KC_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-$(grep -E '^KEYCLOAK_ADMIN_PASSWORD=' .env | cut -d= -f2-)}"
export KC_ADMIN_PASSWORD

kcadm() {
  docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config
}

# kcadm only takes the password as an argument. It travels as an environment variable (`-e NAME` copies it
# from this shell) and is expanded inside the container, so it is never on this host's command line.
docker compose exec -T -e KC_ADMIN_PASSWORD keycloak sh -c \
  'exec /opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config --server http://localhost:8080 --realm master --user admin --password "$KC_ADMIN_PASSWORD"' >/dev/null

id="$(kcadm get clients -r stockrush -q clientId=stockrush-web --fields id --format csv --noquotes | tr -d '\r')"
if [ -z "$id" ]; then
  echo "Client stockrush-web not found in realm stockrush" >&2
  exit 1
fi

kcadm update "clients/$id" -r stockrush \
  -s 'redirectUris=["https://localhost:8443/*","https://127.0.0.1:8443/*"]' \
  -s 'webOrigins=["https://localhost:8443","https://127.0.0.1:8443"]' \
  -s 'attributes."post.logout.redirect.uris"=https://localhost:8443/*##https://127.0.0.1:8443/*'

echo "stockrush-web now redirects to https://localhost:8443/"
