#!/usr/bin/env bash
# Runs the backend test suite.
# Integration tests that need PostgreSQL use Testcontainers and therefore require a
# running Docker daemon; unit and slice tests do not.
source "$(dirname "$0")/lib.sh"

require_java
cd "$BACKEND_DIR"
log "backend tests"
./mvnw -B test "$@"
