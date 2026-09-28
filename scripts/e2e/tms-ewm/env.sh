#!/usr/bin/env bash
# Shared settings for the local TMS <-> EWM end-to-end harness. Sourced, not executed.
# Everything is local and disposable: two database containers this harness creates, two JVMs on
# localhost and a JWKS file server on loopback. No shared database, no Supabase project.

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMS_ROOT="$(cd "$E2E_DIR/../../.." && pwd)"
: "${EWM_ROOT:=$TMS_ROOT/../IACLAUDE/WMS-by-EBIM-tms-connector}"
: "${E2E_WORK:=/tmp/tms-ewm-e2e}"           # generated keys, secrets, logs, pids, state
export E2E_WORK EWM_ROOT TMS_ROOT E2E_DIR

TMS_DB_CONTAINER=tmsewm-e2e-tms-db
EWM_DB_CONTAINER=tmsewm-e2e-ewm-db
TMS_DB_PORT=55440
EWM_DB_PORT=55441
JWKS_PORT=55499
TMS_PORT=8080
EWM_PORT=8081
# Throwaway passwords of throwaway containers bound to loopback; not credentials of anything real.
TMS_DB_PASSWORD=e2e_owner_pw
EWM_DB_PASSWORD=e2e_ewm_pw

TMS_JAR="$TMS_ROOT/backend/tms-api/target/tms-api-0.1.0-SNAPSHOT.jar"
EWM_JAR="$EWM_ROOT/backend/wms-api/target/wms-api-0.1.0-SNAPSHOT.jar"

log()  { printf '\n==> %s\n' "$*"; }
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

wait_http() { # url pattern seconds pidfile
    local url=$1 pattern=$2 secs=$3 pidfile=${4:-}
    for _ in $(seq 1 "$secs"); do
        curl -s "$url" 2>/dev/null | grep -q "$pattern" && return 0
        if [ -n "$pidfile" ] && ! kill -0 "$(cat "$pidfile")" 2>/dev/null; then return 1; fi
        sleep 1
    done
    return 1
}

start_tms() {
    [ -f "$TMS_JAR" ] || fail "TMS jar missing: (cd backend/tms-api && ./mvnw -q -B -DskipTests package)"
    ( cd "$TMS_ROOT/backend/tms-api" || exit 1
      env TMS_DB_URL="jdbc:postgresql://localhost:$TMS_DB_PORT/tms_e2e" TMS_DB_USERNAME=postgres \
      TMS_DB_PASSWORD="$TMS_DB_PASSWORD" \
      TMS_SUPABASE_JWT_ISSUER_URI="http://127.0.0.1:$JWKS_PORT/auth/v1" \
      TMS_SUPABASE_JWKS_URI="http://127.0.0.1:$JWKS_PORT/.well-known/jwks.json" \
      TMS_WEBHOOK_SECRET_KEY="$(cat "$E2E_WORK/tms-webhook-key")" \
      TMS_WEBHOOK_ALLOW_INSECURE_TARGETS=true TMS_WEBHOOK_ALLOW_PRIVATE_TARGETS=true \
      TMS_WEBHOOK_RETRY_BASE_DELAY=${TMS_WEBHOOK_RETRY_BASE_DELAY:-20s} \
      TMS_WEBHOOK_RETRY_MAX_DELAY=${TMS_WEBHOOK_RETRY_MAX_DELAY:-2m} \
      TMS_WEBHOOK_POLL_INTERVAL=${TMS_WEBHOOK_POLL_INTERVAL:-3s} \
      nohup java -jar "$TMS_JAR" --spring.profiles.active=local --server.port=$TMS_PORT \
        >> "$E2E_WORK/tms.log" 2>&1 < /dev/null &
      echo $! > "$E2E_WORK/tms.pid" )
    wait_http "http://localhost:$TMS_PORT/actuator/health/readiness" '"UP"' 120 "$E2E_WORK/tms.pid" \
        || fail "TMS did not become ready; see $E2E_WORK/tms.log"
}

start_ewm() {
    [ -f "$EWM_JAR" ] || fail "EWM jar missing: (cd $EWM_ROOT/backend/wms-api && ./mvnw -q -B -DskipTests package)"
    ( cd "$EWM_ROOT/backend/wms-api" || exit 1
      env SERVER_PORT=$EWM_PORT SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$EWM_DB_PORT/wms" \
      SPRING_DATASOURCE_USERNAME=wms_app SPRING_DATASOURCE_PASSWORD="$EWM_DB_PASSWORD" \
      WMS_INTEGRATION_SECRET_KEY="$(cat "$E2E_WORK/ewm-secret-key")" \
      WMS_OUTBOUND_POLL_INTERVAL=PT3S WMS_OUTBOUND_RETRY_BACKOFF=${WMS_OUTBOUND_RETRY_BACKOFF:-PT5S} \
      WMS_TMS_POLL_INTERVAL=PT3S WMS_TMS_RETRY_BACKOFF=PT5S WMS_TMS_RETRY_MAX_BACKOFF=PT1M \
      WMS_AUTH_MODE=jwt WMS_AUTH_JWT_MODE=jwks \
      WMS_AUTH_JWT_ISSUER="http://127.0.0.1:$JWKS_PORT/auth/v1" \
      WMS_AUTH_JWT_JWK_SET_URI="http://127.0.0.1:$JWKS_PORT/.well-known/jwks.json" \
      nohup java -jar "$EWM_JAR" --spring.profiles.active=local \
        "--wms.integration.outbound.transport.private-network-allowlist=127.0.0.1/32,::1/128" \
        >> "$E2E_WORK/ewm.log" 2>&1 < /dev/null &
      echo $! > "$E2E_WORK/ewm.pid" )
    wait_http "http://localhost:$EWM_PORT/actuator/health" '"UP"' 180 "$E2E_WORK/ewm.pid" \
        || fail "EWM did not become healthy; see $E2E_WORK/ewm.log"
}

stop_pid() { # name (tms|ewm|jwks); also stops whatever of ours still listens on its port
    local f="$E2E_WORK/$1.pid" port pids
    case $1 in tms) port=$TMS_PORT;; ewm) port=$EWM_PORT;; jwks) port=$JWKS_PORT;; esac
    pids="$( [ -f "$f" ] && cat "$f") $(lsof -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null)"
    for p in $(echo $pids); do  # $(...) so zsh splits it too
        # only processes started from this harness: java -jar tms/wms jar, or the jwks helper
        if ps -o command= -p "$p" 2>/dev/null | grep -Eq 'tms-api-.*\.jar|wms-api-.*\.jar|jwt_tool\.py serve'; then
            kill "$p" 2>/dev/null
            for _ in $(seq 1 30); do kill -0 "$p" 2>/dev/null || break; sleep 1; done
        fi
    done
    rm -f "$f"
}
