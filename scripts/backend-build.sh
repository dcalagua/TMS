#!/usr/bin/env bash
# Full backend build: compile, test and package the executable jar.
source "$(dirname "$0")/lib.sh"

require_java
cd "$BACKEND_DIR"
log "backend build (clean verify)"
./mvnw -B clean verify "$@"
ARTIFACT="$(find "$BACKEND_DIR/target" -maxdepth 1 -name '*.jar' ! -name '*.original' 2>/dev/null | head -1)"
log "artifact: ${ARTIFACT:-none}"
