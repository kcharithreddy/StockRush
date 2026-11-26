#!/bin/bash
# Creates the two database roles the shop uses. Runs once, on the first start of an empty Postgres volume.
#
#   flashsale_migrator  owns the schema; only the one-shot db-migrate container (Flyway) logs in as it.
#   DB_USER             what the services connect as: reads and writes rows, cannot create, alter or drop
#                       anything. Each migration grants it exactly what it needs.
#
# The tables themselves are created by Flyway from infra/db/migration, not here.
set -euo pipefail

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_user="$APP_DB_USER" -v app_password="$APP_DB_PASSWORD" \
     -v migrator_password="$MIGRATOR_DB_PASSWORD" <<'SQL'
CREATE ROLE flashsale_migrator LOGIN PASSWORD :'migrator_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO flashsale_migrator;
GRANT USAGE ON SCHEMA public TO :"app_user";
SQL
