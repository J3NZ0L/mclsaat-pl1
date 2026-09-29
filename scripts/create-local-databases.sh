#!/usr/bin/env bash
#
# Creates the three databases, their three logins and the ops console's read-only grant, on a
# PostgreSQL server you already have. One-off; docker compose does the same thing through
# docker/postgres-init.sql.
#
# Needs a superuser connection. Set PGHOST, PGPORT, PGUSER and PGPASSWORD as usual for psql; the
# defaults assume a local server and the postgres superuser.
#
#   scripts/create-local-databases.sh
#   PGPORT=55432 PGUSER=postgres scripts/create-local-databases.sh
#
# Safe to re-run: every object is created only if it is missing, and no data is touched.

set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
export PGHOST PGPORT PGUSER

psql_super() {
  psql --no-psqlrc --quiet --set ON_ERROR_STOP=1 "$@"
}

echo "Connecting to postgres://${PGUSER}@${PGHOST}:${PGPORT}/postgres"
if ! psql_super -d postgres -c 'SELECT 1' >/dev/null 2>&1; then
  echo "error: cannot connect. Is PostgreSQL running, and are PGHOST/PGPORT/PGUSER/PGPASSWORD set?" >&2
  exit 1
fi

# CREATE ROLE and CREATE DATABASE have no IF NOT EXISTS, so each one is guarded by a lookup. The
# roles are created through a DO block; the databases cannot be, because CREATE DATABASE is not
# allowed inside a transaction.
create_role() {
  local role="$1" password="$2"
  psql_super -d postgres <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${role}') THEN
        CREATE ROLE ${role} WITH LOGIN PASSWORD '${password}';
        RAISE NOTICE 'created role ${role}';
    ELSE
        RAISE NOTICE 'role ${role} already exists';
    END IF;
END
\$\$;
SQL
}

create_database() {
  local database="$1" owner="$2"
  if psql_super -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '${database}'" | grep -q 1; then
    echo "NOTICE:  database ${database} already exists"
  else
    psql_super -d postgres -c "CREATE DATABASE ${database} OWNER ${owner}"
    echo "NOTICE:  created database ${database}"
  fi
}

# subsystem 1
create_role catalog_app catalog_app
create_database catalogdb catalog_app

# subsystem 2 - Flowable's engine tables live in this database too
create_role activation_app activation_app
create_database flowabledb activation_app

# subsystem 3
create_role billing_app billing_app
create_database billingdb billing_app

# The ops console's read-only login into the catalog's schema. This is the "direct database access"
# interface style, granted explicitly rather than smuggled in through a shared password.
create_role ops_reader ops_reader

psql_super -d catalogdb <<'SQL'
CREATE SCHEMA IF NOT EXISTS catalog AUTHORIZATION catalog_app;
GRANT CONNECT ON DATABASE catalogdb TO ops_reader;
GRANT USAGE ON SCHEMA catalog TO ops_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO ops_reader;
-- Flyway creates the tables later, as catalog_app; this makes them readable when it does.
ALTER DEFAULT PRIVILEGES FOR ROLE catalog_app IN SCHEMA catalog
    GRANT SELECT ON TABLES TO ops_reader;
SQL

echo
echo "Ready:"
echo "  catalogdb   owned by catalog_app,    readable by ops_reader"
echo "  flowabledb  owned by activation_app"
echo "  billingdb   owned by billing_app"
echo
echo "Next: scripts/run-local.sh"
