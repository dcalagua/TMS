#!/usr/bin/env bash
# =============================================================================
# Local end-to-end run: TMS by EBIM <-> EWM by EBIM (Warehouse Execution v1).
#
#   scripts/e2e/tms-ewm/run.sh              # up + all scenarios + down
#   scripts/e2e/tms-ewm/run.sh up           # disposable infra, both backends, master data, link
#   scripts/e2e/tms-ewm/run.sh scenarios [s1 s3 ...]
#   scripts/e2e/tms-ewm/run.sh down         # stop both backends + JWKS, remove the two containers
#
# Disposable by construction: two database containers this script creates
# (tmsewm-e2e-tms-db, tmsewm-e2e-ewm-db), two JVMs and a JWKS file server on
# localhost. Keys and secrets are generated per run into $E2E_WORK (default
# /tmp/tms-ewm-e2e), never into the repository. No Supabase project, no shared DB.
#
# KEEP=1 leaves everything running after the default run, for inspection.
# =============================================================================
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

build_if_missing() {
    if [ ! -f "$TMS_JAR" ] || [ "${BUILD:-0}" = 1 ]; then
        log "Building TMS"; (cd "$TMS_ROOT/backend/tms-api" && ./mvnw -q -B -DskipTests package)
    fi
    if [ ! -f "$EWM_JAR" ] || [ "${BUILD:-0}" = 1 ]; then
        log "Building EWM"; (cd "$EWM_ROOT/backend/wms-api" && ./mvnw -q -B -DskipTests package)
    fi
}

up() {
    docker info >/dev/null 2>&1 || fail "Docker is not running (open -a Docker)."
    [ -d "$EWM_ROOT/backend/wms-api" ] || fail "EWM checkout not found at $EWM_ROOT (set EWM_ROOT)."
    for p in $TMS_PORT $EWM_PORT $JWKS_PORT $TMS_DB_PORT $EWM_DB_PORT; do
        if lsof -iTCP:"$p" -sTCP:LISTEN -t >/dev/null 2>&1; then fail "port $p is busy (run.sh down?)"; fi
    done
    build_if_missing
    mkdir -p "$E2E_WORK"; chmod 700 "$E2E_WORK"
    rm -f "$E2E_WORK/state.env"
    [ -f "$E2E_WORK/tms-webhook-key" ] || (umask 077; openssl rand -base64 48 | tr -d '\n' > "$E2E_WORK/tms-webhook-key")
    [ -f "$E2E_WORK/ewm-secret-key" ]  || (umask 077; openssl rand -base64 48 | tr -d '\n' > "$E2E_WORK/ewm-secret-key")

    log "Databases (disposable containers)"
    docker rm -f "$TMS_DB_CONTAINER" "$EWM_DB_CONTAINER" >/dev/null 2>&1 || true
    docker run -d --name "$TMS_DB_CONTAINER" -e POSTGRES_PASSWORD="$TMS_DB_PASSWORD" -e POSTGRES_DB=tms_e2e \
        -p 127.0.0.1:$TMS_DB_PORT:5432 postgis/postgis:17-3.5 >/dev/null
    docker run -d --name "$EWM_DB_CONTAINER" -e POSTGRES_DB=wms -e POSTGRES_USER=wms_app \
        -e POSTGRES_PASSWORD="$EWM_DB_PASSWORD" -e POSTGRES_INITDB_ARGS="--encoding=UTF8 --locale=C" \
        -p 127.0.0.1:$EWM_DB_PORT:5432 postgres:17-alpine >/dev/null
    for c in "$TMS_DB_CONTAINER" "$EWM_DB_CONTAINER"; do
        for _ in $(seq 1 60); do docker exec "$c" pg_isready -q >/dev/null 2>&1 && break; sleep 1; done
    done
    sleep 3   # the postgis image restarts once after its init scripts

    log "JWKS (stands in for Supabase Auth; RS256 key generated for this run)"
    python3 "$E2E_DIR/lib/jwt_tool.py" keygen "$E2E_WORK"
    (nohup python3 "$E2E_DIR/lib/jwt_tool.py" serve "$E2E_WORK" "$JWKS_PORT" > "$E2E_WORK/jwks.log" 2>&1 < /dev/null &
     echo $! > "$E2E_WORK/jwks.pid")
    wait_http "http://127.0.0.1:$JWKS_PORT/.well-known/jwks.json" '"keys"' 20 || fail "JWKS server did not start"

    log "TMS on :$TMS_PORT (Flyway migrates the empty database)"
    start_tms
    docker exec -i "$TMS_DB_CONTAINER" psql -q -U postgres -d tms_e2e -v ON_ERROR_STOP=1 \
        < "$E2E_DIR/lib/seed_tms_identity.sql"
    cat > "$E2E_WORK/state.env" <<EOF
TMS_ORG=0e2e0000-0000-4000-8000-000000000001
TMS_CO=0e2e0000-0000-4000-8000-000000000002
TMS_SUB=0e2e0000-0000-4000-8000-0000000000a1
EOF
    chmod 600 "$E2E_WORK/state.env"

    log "EWM on :$EWM_PORT (Flyway first: it refuses a non-empty public schema without history)"
    start_ewm
    docker exec -i "$EWM_DB_CONTAINER" psql -q -U wms_app -d wms -v ON_ERROR_STOP=1 \
        < "$E2E_DIR/lib/ewm_supabase_owned_tables.sql" 2>&1 | grep -v NOTICE || true

    log "Master data and the link between the two products"
    (cd "$E2E_DIR/lib" && python3 setup_tms.py && python3 setup_ewm.py && python3 setup_link.py)
    log "Up. TMS http://localhost:$TMS_PORT  EWM http://localhost:$EWM_PORT  work dir $E2E_WORK"
}

scenarios() {
    (cd "$E2E_DIR/lib" && python3 scenarios.py "$@")
}

down() {
    log "Stopping TMS, EWM and the JWKS server; removing the two e2e containers"
    stop_pid tms; stop_pid ewm; stop_pid jwks
    docker rm -f "$TMS_DB_CONTAINER" "$EWM_DB_CONTAINER" >/dev/null 2>&1 || true
}

case "${1:-all}" in
    up) up ;;
    down) down ;;
    scenarios) shift; scenarios "$@" ;;
    all)
        up
        rc=0; scenarios || rc=$?
        [ "${KEEP:-0}" = 1 ] || down
        exit $rc ;;
    *) fail "usage: run.sh [all|up|scenarios [s1..s9]|down]" ;;
esac
