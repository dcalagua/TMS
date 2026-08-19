#!/usr/bin/env bash
# Runs the frontend unit/component test suite once (CI mode).
source "$(dirname "$0")/lib.sh"

require_node
ensure_frontend_deps
log "frontend tests"
npm test -- "$@"
