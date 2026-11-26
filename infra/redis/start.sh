#!/bin/sh
# Renders the ACL template with the passwords from the environment (never written into the image or git),
# then starts Redis with it. Passwords must not contain whitespace, '|' or '\'.
set -eu
for var in REDIS_PASSWORD REDIS_SALE_PASSWORD REDIS_ORDER_PASSWORD; do
  eval "value=\${$var:-}"
  [ -n "$value" ] || { echo "$var is not set" >&2; exit 1; }
done
# ACL files allow neither comments nor blank lines, so the template's explanations are stripped here.
sed -e '/^#/d' -e '/^[[:space:]]*$/d' \
    -e "s|\${REDIS_PASSWORD}|$REDIS_PASSWORD|" \
    -e "s|\${REDIS_SALE_PASSWORD}|$REDIS_SALE_PASSWORD|" \
    -e "s|\${REDIS_ORDER_PASSWORD}|$REDIS_ORDER_PASSWORD|" \
    /etc/redis/users.acl.template > /tmp/users.acl
chmod 600 /tmp/users.acl
exec redis-server --appendonly yes --aclfile /tmp/users.acl
