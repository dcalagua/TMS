#!/usr/bin/env bash
# Starts the backend on the local profile (http://localhost:8080).
# Requires a reachable PostgreSQL; see supabase/README.md or backend/tms-api/.env.example.
source "$(dirname "$0")/lib.sh"

require_java
cd "$BACKEND_DIR"
log "starting tms-api on the 'local' profile"
./mvnw -B spring-boot:run -Dspring-boot.run.profiles="${SPRING_PROFILES_ACTIVE:-local}" "$@"
