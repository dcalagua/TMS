#!/usr/bin/env bash
# Shared helpers for the TMS developer scripts.
# Sourced, never executed directly. Written for bash 3.2 (macOS default).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend/tms-api"
FRONTEND_DIR="$REPO_ROOT/frontend/tms-web"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

require_java() {
  command -v java >/dev/null 2>&1 || fail "java not found on PATH. Install a JDK 21."
  local version
  version="$(java -version 2>&1 | head -1)"
  case "$version" in
    *\"21.*|*\"2[2-9].*) : ;;
    *) warn "expected Java 21+, found: $version" ;;
  esac
}

require_node() {
  command -v node >/dev/null 2>&1 || fail "node not found on PATH. Install Node.js 20+."
}

# Installs frontend dependencies only when they are missing or out of date.
ensure_frontend_deps() {
  cd "$FRONTEND_DIR"
  if [ ! -d node_modules ]; then
    log "installing frontend dependencies"
    if [ -f package-lock.json ]; then npm ci; else npm install; fi
  elif [ package-lock.json -nt node_modules ]; then
    log "package-lock.json is newer than node_modules; reinstalling"
    npm ci
  fi
}
