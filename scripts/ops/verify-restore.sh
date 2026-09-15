#!/usr/bin/env bash
# Read-only check of a restored TMS database. The gate a logical restore is allowed through.
#
# WHY THIS EXISTS
# ---------------
# pg_dump does not carry cluster-global roles. Restored into a cluster without `tms_app`, a TMS
# database keeps its Flyway history, its rows and its RLS flags, and loses every policy and every
# grant - while pg_restore exits 1 exactly as it does on a good restore, the backend boots, Flyway
# validates clean and readiness answers UP. See docs/operations/BACKUP_AND_RESTORE.md.
#
# This script runs the checks of that page's section 8 and fails on the ones that discriminate.
# Given a fingerprint taken from the source before the dump, it also requires the target to match.
#
# It never writes. Every session runs with default_transaction_read_only=on, and the one check that
# enters `tms_app` does so with SET LOCAL inside a READ ONLY transaction that is rolled back.
#
# USAGE
#   scripts/ops/verify-restore.sh [--baseline <file>] -- <psql connection arguments>
#
#   --baseline <file>  A fingerprint previously printed by this script against the SOURCE database.
#                      Given, every key in it must match the target
#   --                 Everything after it is passed to psql verbatim, e.g.
#                        -h localhost -p 55433 -U tms_owner -d tms_restored
#                      libpq variables (PGHOST, PGPORT, PGUSER, PGDATABASE) work too. psql is run
#                      with -w (never prompt): supply the password via PGPASSWORD or ~/.pgpass
#
#   Capture a source fingerprint:
#     scripts/ops/verify-restore.sh -- -h <host> -U <owner> -d <source> > source.fingerprint
#   Check a restore against it:
#     scripts/ops/verify-restore.sh --baseline source.fingerprint -- -h <host> -U <owner> -d <target>
#
#   Fingerprint lines (key=value) go to stdout; check results go to stderr.
#
# Run it as the role the backend connects as: "can enter tms_app" is a property of that role. A
# superuser can SET ROLE to anything and would pass that check regardless.
#
# EXIT
#   0  every check passed (and the baseline matched, if given)
#   1  at least one check failed; do not put this database in service
#   2  usage error, psql missing, or the database could not be reached
#
# NOT VERIFIED: this wrapper has been syntax-checked (bash -n) and never run against a database.
# Its queries are the ones executed on 2026-09-15 on PostgreSQL 17.10 + PostGIS 3.6.2 and recorded
# in BACKUP_AND_RESTORE.md; the plumbing around them is new.
set -euo pipefail

BASELINE=""
PSQL_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --baseline) BASELINE="${2:-}"; shift 2 ;;
    --) shift; PSQL_ARGS=("$@"); break ;;
    -h|--help) sed -n '2,44p' "$0"; exit 0 ;;
    *) printf 'unknown argument: %s (try --help)\n' "$1" >&2; exit 2 ;;
  esac
done

if [ -n "$BASELINE" ] && [ ! -r "$BASELINE" ]; then
  printf '[fail] baseline file not readable: %s\n' "$BASELINE" >&2
  exit 2
fi

command -v psql >/dev/null 2>&1 || { printf '[fail] psql is not on PATH\n' >&2; exit 2; }

# Read-only for every statement of every session this script opens.
export PGOPTIONS="${PGOPTIONS:+$PGOPTIONS }-c default_transaction_read_only=on"

FAILURES=0
FINGERPRINT=()

pass() { printf '\033[1;32m[ ok ]\033[0m %s\n' "$1" >&2; }
fail() { printf '\033[1;31m[fail]\033[0m %s\n' "$1" >&2; FAILURES=$((FAILURES + 1)); }
note() { printf '\033[1;34m[info]\033[0m %s\n' "$1" >&2; }

# One scalar, or the literal ERROR if the statement failed. Never aborts the script.
q() {
  local out
  if out=$(psql -X -w -q -A -t -v ON_ERROR_STOP=1 ${PSQL_ARGS[@]+"${PSQL_ARGS[@]}"} -c "$1" 2>/dev/null); then
    printf '%s' "$out" | tr -d '\r' | sed -n '1p'
  else
    printf 'ERROR'
  fi
}

record() { FINGERPRINT+=("$1=$2"); printf '%s=%s\n' "$1" "$2"; }

# expect <label> <actual> <expected>
expect() {
  if [ "$2" = "$3" ]; then pass "$1 ($2)"; else fail "$1: expected '$3', got '$2'"; fi
}

# ---------------------------------------------------------------------------
# 0. Reachability
# ---------------------------------------------------------------------------
SERVER_VERSION=$(q "SELECT current_setting('server_version')")
if [ "$SERVER_VERSION" = "ERROR" ] || [ -z "$SERVER_VERSION" ]; then
  printf '[fail] could not query the database with the given psql arguments\n' >&2
  exit 2
fi
note "server $SERVER_VERSION, database $(q "SELECT current_database()"), connected as $(q "SELECT current_user")"
note "PostGIS $(q "SELECT coalesce((SELECT extversion FROM pg_extension WHERE extname = 'postgis'), 'absent')")"

# ---------------------------------------------------------------------------
# 1. Flyway history (identical on a broken restore: recorded, not discriminating on its own)
# ---------------------------------------------------------------------------
if [ "$(q "SELECT to_regclass('tms.flyway_schema_history') IS NOT NULL")" != "t" ]; then
  fail "tms.flyway_schema_history does not exist - nothing was restored into schema tms"
  printf '\n%d check(s) failed\n' "$FAILURES" >&2
  exit 1
fi

record migrations   "$(q "SELECT count(*) FROM tms.flyway_schema_history")"
record max_rank     "$(q "SELECT max(installed_rank) FROM tms.flyway_schema_history")"
record history_md5  "$(q "SELECT md5((SELECT string_agg(version || ':' || coalesce(checksum::text,'null') || ':' || success::text, ',' ORDER BY installed_rank) FROM tms.flyway_schema_history))")"
record latest_version "$(q "SELECT version FROM tms.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")"
expect "failed migrations in history" "$(q "SELECT count(*) FROM tms.flyway_schema_history WHERE NOT success")" "0"

# ---------------------------------------------------------------------------
# 2. The runtime role - the part pg_dump does not carry
# ---------------------------------------------------------------------------
ROLE_EXISTS=$(q "SELECT count(*) FROM pg_roles WHERE rolname = 'tms_app'")
record tms_app_exists "$ROLE_EXISTS"
if [ "$ROLE_EXISTS" != "1" ]; then
  fail "role tms_app does not exist - the dump did not carry it. Recovery: BACKUP_AND_RESTORE.md section 6"
else
  pass "role tms_app exists"
  expect "tms_app is NOLOGIN" "$(q "SELECT rolcanlogin FROM pg_roles WHERE rolname = 'tms_app'")" "f"

  ENTERED=$(psql -X -w -q -A -t -v ON_ERROR_STOP=1 ${PSQL_ARGS[@]+"${PSQL_ARGS[@]}"} 2>/dev/null <<'SQL' | tr -d '\r' | sed -n '1p' || true
BEGIN READ ONLY;
SET LOCAL ROLE tms_app;
SELECT current_user;
ROLLBACK;
SQL
)
  if [ "$ENTERED" = "tms_app" ]; then
    pass "the connection role can SET ROLE tms_app"
  else
    fail "the connection role cannot SET ROLE tms_app - missing GRANT tms_app TO <connection role> WITH SET TRUE"
  fi
  if [ "$(q "SELECT rolsuper FROM pg_roles WHERE rolname = current_user")" = "t" ]; then
    note "connected as a superuser: the SET ROLE check above proves nothing about the backend's role"
  fi
fi

# ---------------------------------------------------------------------------
# 3. Policies, RLS, grants
# ---------------------------------------------------------------------------
POLICIES=$(q "SELECT count(*) FROM pg_policies WHERE schemaname = 'tms'")
record policies "$POLICIES"
if [ "$POLICIES" = "0" ] || [ "$POLICIES" = "ERROR" ]; then
  fail "no policies in schema tms ($POLICIES) - they were dropped with the role. Recovery: section 6"
else
  pass "policies in schema tms ($POLICIES)"
fi

record policies_to_tms_app "$(q "SELECT count(*) FROM pg_policies WHERE schemaname = 'tms' AND 'tms_app'::name = ANY (roles)")"
record rls_tables "$(q "SELECT count(*) FILTER (WHERE relrowsecurity) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'tms' AND c.relkind = 'r'")"
record default_acls_to_tms_app "$(q "SELECT count(*) FROM pg_default_acl d JOIN pg_namespace n ON n.oid = d.defaclnamespace WHERE n.nspname = 'tms' AND d.defaclacl::text LIKE '%tms_app=%'")"

if [ "$ROLE_EXISTS" = "1" ]; then
  READABLE=$(q "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'tms' AND c.relkind = 'r' AND has_table_privilege('tms_app', c.oid, 'SELECT')")
  record tables_readable_by_tms_app "$READABLE"
  if [ "$READABLE" = "0" ] || [ "$READABLE" = "ERROR" ]; then
    fail "tms_app can read no table in schema tms ($READABLE) - the grants were dropped with the role"
  else
    pass "tables readable by tms_app ($READABLE)"
  fi

  expect "V49 transport_order_number_seq USAGE" "$(q "SELECT has_sequence_privilege('tms_app','tms.transport_order_number_seq','USAGE')")" "t"
  expect "V49 planning_run_number_seq USAGE"    "$(q "SELECT has_sequence_privilege('tms_app','tms.planning_run_number_seq','USAGE')")" "t"
  expect "V49 shipment_number_seq USAGE"        "$(q "SELECT has_sequence_privilege('tms_app','tms.shipment_number_seq','USAGE')")" "t"
  expect "V50 shipment_outbox_event UPDATE revoked" "$(q "SELECT has_table_privilege('tms_app','tms.shipment_outbox_event','UPDATE')")" "f"
  expect "V50 settlement_approval DELETE revoked"   "$(q "SELECT has_table_privilege('tms_app','tms.settlement_approval','DELETE')")" "f"
  expect "V50 role INSERT revoked"                  "$(q "SELECT has_table_privilege('tms_app','tms.role','INSERT')")" "f"
  expect "role SELECT granted"                      "$(q "SELECT has_table_privilege('tms_app','tms.role','SELECT')")" "t"
  expect "carrier INSERT granted"                   "$(q "SELECT has_table_privilege('tms_app','tms.carrier','INSERT')")" "t"
fi

# ---------------------------------------------------------------------------
# 4. Baseline comparison
# ---------------------------------------------------------------------------
if [ -n "$BASELINE" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    case "$line" in ''|\#*) continue ;; esac
    key=${line%%=*}
    want=${line#*=}
    got=""
    for entry in ${FINGERPRINT[@]+"${FINGERPRINT[@]}"}; do
      if [ "${entry%%=*}" = "$key" ]; then got=${entry#*=}; fi
    done
    if [ "$got" = "$want" ]; then
      pass "baseline $key ($got)"
    else
      fail "baseline $key: source '$want', target '${got:-<not measured>}'"
    fi
  done < "$BASELINE"
else
  note "no --baseline given: counts were recorded, not compared with the source"
fi

# ---------------------------------------------------------------------------
if [ "$FAILURES" -gt 0 ]; then
  printf '\n%d check(s) failed - do not put this database in service\n' "$FAILURES" >&2
  exit 1
fi
printf '\n%s\n' "all checks passed. Now start the backend and find TenantRuntimeRoleCheck at INFO: 'tms_app' can be entered" >&2
exit 0
