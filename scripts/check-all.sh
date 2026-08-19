#!/usr/bin/env bash
# Every check this repository can run locally, in the order that fails fastest.
# Exits non-zero if any stage fails; prints a summary either way.
source "$(dirname "$0")/lib.sh"

FAILED=""

run_stage() {
  local name="$1"; shift
  log "$name"
  if "$@"; then
    printf '\033[1;32m[ok]\033[0m %s\n' "$name"
  else
    printf '\033[1;31m[fail]\033[0m %s\n' "$name" >&2
    FAILED="$FAILED $name"
  fi
}

require_java
require_node
ensure_frontend_deps

run_stage "frontend typecheck" npm --prefix "$FRONTEND_DIR" run typecheck
run_stage "frontend lint"      npm --prefix "$FRONTEND_DIR" run lint
run_stage "frontend tests"     npm --prefix "$FRONTEND_DIR" test
run_stage "frontend build"     npm --prefix "$FRONTEND_DIR" run build
run_stage "backend verify"     "$BACKEND_DIR/mvnw" -B -f "$BACKEND_DIR/pom.xml" clean verify

if ! docker info >/dev/null 2>&1; then
  warn "Docker daemon is not running: Testcontainers integration tests were not executed."
fi

echo
if [ -n "$FAILED" ]; then
  fail "failed stages:$FAILED"
fi
log "all checks passed"
