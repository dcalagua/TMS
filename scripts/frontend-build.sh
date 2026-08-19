#!/usr/bin/env bash
# Frontend typecheck, lint and production build.
source "$(dirname "$0")/lib.sh"

require_node
ensure_frontend_deps
log "frontend typecheck"
npm run typecheck
log "frontend lint"
npm run lint
log "frontend production build"
npm run build
log "bundle: $FRONTEND_DIR/dist"
