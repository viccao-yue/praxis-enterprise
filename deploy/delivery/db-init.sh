#!/usr/bin/env bash
# Maintenance-only: migrate schema, ensure workdsh_app role, apply runtime grants.
set -euo pipefail

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-praxis_maint}"
export PGDATABASE="${PGDATABASE:-kaiwu_praxis}"
export PGPASSWORD="${POSTGRES_MAINT_PASSWORD:?POSTGRES_MAINT_PASSWORD is required}"

echo "db-init: waiting for postgres..."
for _ in $(seq 1 90); do
  if pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE"

echo "db-init: applying migrations..."
psql -v ON_ERROR_STOP=1 -f /init/schema-migrate.sql

pw="${JDBC_PASSWORD:?JDBC_PASSWORD is required}"
pw_escaped="${pw//\'/\'\'}"
echo "db-init: ensuring workdsh_app role..."
psql -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'workdsh_app') THEN
    CREATE ROLE workdsh_app LOGIN PASSWORD '${pw_escaped}' NOSUPERUSER NOCREATEDB NOCREATEROLE;
  ELSE
    ALTER ROLE workdsh_app WITH PASSWORD '${pw_escaped}';
  END IF;
END
\$\$;
SQL

echo "db-init: applying runtime grants..."
psql -v ON_ERROR_STOP=1 -f /init/runtime-grants.sql
echo "db-init: complete"
