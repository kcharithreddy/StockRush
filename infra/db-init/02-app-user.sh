#!/bin/bash
# Creates the least-privilege role the services use. It can read and write rows,
# but cannot create, alter or drop tables, and is not a superuser.
# Runs once, on the first start of an empty Postgres volume.
set -euo pipefail

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_user="$APP_DB_USER" -v app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app_user";

GRANT SELECT ON products, flash_sales TO :"app_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON orders TO :"app_user";
GRANT USAGE, SELECT ON SEQUENCE orders_id_seq TO :"app_user";
SQL
