#!/usr/bin/env bash
# PostgreSQL in Docker for the reference platform, with its own junit tenant.
# Usage: scripts/dev/refdb.sh up|down|use-postgres|use-hsqldb|status
#   up            start postgres:16 (container stripe-refdb, host port 5433, volume stripe-refdb-data)
#                 and create the databases hybris and hybris_junit (user hybris)
#   down          stop the container; the volume keeps the data
#   use-postgres  point local.properties and local_tenant_junit.properties at PostgreSQL
#   use-hsqldb    remove what use-postgres added; the platform uses HSQLDB again
#   status        show the container and the database that the platform uses
# The platform is $HYBRIS_PLATFORM, or the folder ../stripe-ref-platform next to this repo.
# The database password is local and fixed. The script does not print it.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
compose_file="$root/tools/refdb/docker-compose.yml"
platform="${HYBRIS_PLATFORM:-$root/../stripe-ref-platform}"
platform="$(cd "$platform" 2>/dev/null && pwd || echo "$platform")"
config_dir="$platform/hybris/config"
local_props="$config_dir/local.properties"
junit_props="$config_dir/local_tenant_junit.properties"
dbdriver_dir="$platform/hybris/bin/platform/lib/dbdriver"

begin_marker='# >>> refdb'
begin_marker_nl='# >>> refdb (newline added)'
end_marker='# <<< refdb'
junit_header='# Written by scripts/dev/refdb.sh use-postgres. use-hsqldb removes this file.'

driver_version='42.7.11'
driver_jar="postgresql-$driver_version.jar"
driver_sha256='1981b31d3993c58702783c1cddf10a34e48c1f413d70ff1cb6def0a143484647'
driver_url="https://repo1.maven.org/maven2/org/postgresql/postgresql/$driver_version/$driver_jar"

usage() {
  echo "Usage: $0 up|down|use-postgres|use-hsqldb|status" >&2
  exit 2
}

need_platform() {
  if [ ! -f "$local_props" ]; then
    echo "local.properties not found in $config_dir. Set HYBRIS_PLATFORM." >&2
    exit 2
  fi
}

db_keys() {
  cat <<EOF
db.url=jdbc:postgresql://localhost:5433/$1
db.driver=org.postgresql.Driver
db.username=hybris
db.password=hybris
db.tableprefix=$2
EOF
}

# Print local.properties without the refdb block. The bytes outside the block stay the same.
props_without_block() {
  perl -0pe '
    s/\n\Q'"$begin_marker_nl"'\E\n.*?^\Q'"$end_marker"'\E\n//sm;
    s/^\Q'"$begin_marker"'\E\n.*?^\Q'"$end_marker"'\E\n//sm;
  ' "$local_props"
}

# Write stdin to the file in place, so the file keeps its mode and owner.
overwrite() {
  local tmp
  tmp="$(mktemp)"
  cat > "$tmp"
  cat "$tmp" > "$1"
  rm -f "$tmp"
}

ensure_driver() {
  if ls "$dbdriver_dir"/postgresql-*.jar >/dev/null 2>&1; then
    return
  fi
  echo "Downloading the PostgreSQL JDBC driver $driver_version to $dbdriver_dir"
  local tmp
  tmp="$(mktemp)"
  curl -fsSL -o "$tmp" "$driver_url"
  if [ "$(shasum -a 256 "$tmp" | cut -d' ' -f1)" != "$driver_sha256" ]; then
    rm -f "$tmp"
    echo "The SHA-256 of $driver_jar is not the expected one." >&2
    exit 1
  fi
  mv "$tmp" "$dbdriver_dir/$driver_jar"
  chmod 644 "$dbdriver_dir/$driver_jar"
}

use_postgres() {
  need_platform
  if [ -f "$junit_props" ] && [ "$(head -n 1 "$junit_props")" != "$junit_header" ]; then
    echo "$junit_props exists and refdb.sh did not write it. Move it away first." >&2
    exit 1
  fi
  ensure_driver

  local rest marker
  rest="$(props_without_block && echo x)"
  rest="${rest%x}"
  marker="$begin_marker"
  if [ -n "$rest" ] && [ "${rest: -1}" != $'\n' ]; then
    rest="$rest"$'\n'
    marker="$begin_marker_nl"
  fi
  {
    printf '%s' "$rest"
    echo "$marker"
    db_keys hybris ''
    echo "$end_marker"
  } | overwrite "$local_props"

  {
    echo "$junit_header"
    db_keys hybris_junit junit_
  } > "$junit_props"

  echo "The platform uses PostgreSQL: database hybris, junit tenant database hybris_junit."
  echo "Run 'ant initialize' and 'ant yunitinit' once on a new database."
}

use_hsqldb() {
  need_platform
  local rest
  rest="$(props_without_block && echo x)"
  printf '%s' "${rest%x}" | overwrite "$local_props"
  if [ -f "$junit_props" ]; then
    if [ "$(head -n 1 "$junit_props")" = "$junit_header" ]; then
      rm -f "$junit_props"
    else
      echo "Kept $junit_props: refdb.sh did not write it." >&2
    fi
  fi
  echo "The platform uses HSQLDB."
}

status() {
  local state
  if ! state="$(docker inspect -f '{{.State.Status}} ({{if .State.Health}}{{.State.Health.Status}}{{else}}no healthcheck{{end}})' stripe-refdb 2>/dev/null)"; then
    state='not created'
  fi
  echo "Container stripe-refdb: $state"
  if [ -f "$local_props" ] && grep -qF "$end_marker" "$local_props"; then
    echo "Platform database: PostgreSQL (localhost:5433/hybris)"
  else
    echo "Platform database: not set by refdb.sh (HSQLDB unless local.properties sets db.url)"
  fi
  if [ -f "$junit_props" ]; then
    echo "Junit tenant: $junit_props"
  fi
}

[ $# -eq 1 ] || usage
case "$1" in
  up)
    docker compose -f "$compose_file" up -d --wait
    docker exec -i stripe-refdb psql -q -v ON_ERROR_STOP=1 -U hybris -d hybris < "$root/tools/refdb/init/01-databases.sql"
    echo "PostgreSQL is up on localhost:5433 (databases hybris and hybris_junit)."
    ;;
  down) docker compose -f "$compose_file" down ;;
  use-postgres) use_postgres ;;
  use-hsqldb) use_hsqldb ;;
  status) status ;;
  *) usage ;;
esac
