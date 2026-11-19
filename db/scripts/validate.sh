#!/usr/bin/env bash
# Clean-install validation: creates a throwaway database, applies every
# migration in order (each in its own transaction, like Flyway), runs the
# invariant test suite and the concurrency race test, then drops everything.
#
#   db/scripts/validate.sh --docker        # recommended / CI: throwaway PostGIS container
#   db/scripts/validate.sh                 # use PG* env vars / local server
#   KEEP_DB=1 db/scripts/validate.sh       # keep the database for inspection (local mode)
#
# Without PostGIS on the target server, geography columns are shimmed to the
# core `point` type so everything else can still be validated. --docker always
# uses real PostGIS.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DB="beavent_validate_$(date +%s)_$$"
WORK="$(mktemp -d)"
CONTAINER=""

cleanup() {
  rm -rf "$WORK"
  if [[ -n "$CONTAINER" ]]; then
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  elif [[ "${KEEP_DB:-0}" != "1" ]]; then
    dropdb --if-exists "$DB" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "${1:-}" == "--docker" ]]; then
  IMAGE="${POSTGIS_IMAGE:-postgis/postgis:17-3.5}"
  CONTAINER="beavent-validate-$$"
  echo "starting $IMAGE ..."
  docker run -d --rm --name "$CONTAINER" --platform linux/amd64 \
    -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=validate \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
  PORT="$(docker port "$CONTAINER" 5432/tcp | head -1 | awk -F: '{print $NF}')"
  export PGHOST=127.0.0.1 PGPORT="$PORT" PGUSER=postgres PGPASSWORD=validate PGDATABASE=postgres
  # The image initialises, then restarts the server; wait for the final start.
  for _ in $(seq 1 120); do
    if docker logs "$CONTAINER" 2>&1 | grep -q "PostgreSQL init process complete" \
       && psql -X -q -Atc 'select 1' >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  psql -X -q -Atc 'select 1' >/dev/null
fi

createdb "$DB"
echo "database: $DB ($(psql -X -d "$DB" -Atc "select current_setting('server_version')"))"

HAS_POSTGIS=$(psql -X -d "$DB" -Atc "select count(*) from pg_available_extensions where name='postgis'")
if [[ "$HAS_POSTGIS" == "0" ]]; then
  echo "WARNING: PostGIS not available — shimming geography(Point,4326) to point for this run"
fi

prepare() {
  local src="$1" dst="$2"
  if [[ "$HAS_POSTGIS" == "0" ]]; then
    sed -e '/CREATE EXTENSION IF NOT EXISTS postgis;/d' \
        -e 's/geography(Point, *4326)/point/g' "$src" > "$dst"
  else
    cp "$src" "$dst"
  fi
}

for f in "$ROOT"/db/migration/V*.sql; do
  name="$(basename "$f")"
  prepare "$f" "$WORK/$name"
  printf '  apply %-50s' "$name"
  psql -X -q -v ON_ERROR_STOP=1 --single-transaction -d "$DB" -f "$WORK/$name" >/dev/null
  echo ok
done

if [[ "$HAS_POSTGIS" != "0" ]]; then
  echo "postgis: $(psql -X -d "$DB" -Atc "select extversion from pg_extension where extname='postgis'")"
fi

echo "objects:"
psql -X -d "$DB" -Atc "
  select '  tables=' || count(*) filter (where c.relkind = 'r')
      || ' rls_tables=' || count(*) filter (where c.relkind = 'r' and c.relrowsecurity)
      || ' schemas=' || count(distinct n.nspname)
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname in ('platform','ref','iam','tenant','team','catalog','crm','events','commerce','scheduling',
                      'retention','stock','payments','ledger','billing','fiscal','marketing','comms','discovery','trust')"

if compgen -G "$ROOT/db/tests/*.sql" > /dev/null; then
  echo "tests:"
  for t in "$ROOT"/db/tests/*.sql; do
    printf '  %-50s' "$(basename "$t")"
    psql -X -q -v ON_ERROR_STOP=1 -d "$DB" -f "$t" >/dev/null
    echo pass
  done
  echo "concurrency:"
  "$ROOT/db/scripts/concurrency_check.sh" "$DB"
fi
echo "VALID"
