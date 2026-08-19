#!/usr/bin/env bash
# Starts the Vite dev server (http://localhost:5173).
source "$(dirname "$0")/lib.sh"

require_node
ensure_frontend_deps
log "starting tms-web dev server"
npm run dev -- "$@"
