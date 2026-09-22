#!/usr/bin/env bash
# Refuses a frontend build whose API coordinates would be wrong once published.
#
# WHY THIS EXISTS
# ---------------
# `frontend/tms-web/src/shared/config/env.ts` reads VITE_API_BASE_URL with a `??` default of
# `http://localhost:8080/api/v1`. VITE_* values are compiled into the bundle, so a build that
# runs without the variable does not fail and does not warn: it *bakes localhost* and publishes
# a frontend whose every business call goes to the visitor's own machine. The failure surfaces
# as "nothing loads", long after the deploy went green.
#
# This is the gate that turns that silent default into a refused build. It runs before
# `npm run build` - in CI and in the Amplify build phase - and `assert-frontend-bundle.sh`
# inspects the produced artefact afterwards, in case this gate is ever skipped.
#
# USAGE
#   scripts/ci/check-frontend-env.sh
#
# INPUT (environment)
#   VITE_API_BASE_URL              required; absolute backend URL, ending in /api/v1
#   VITE_SUPABASE_URL              required; the Supabase project used for authentication
#   VITE_SUPABASE_ANON_KEY         required; anon/publishable key, never a service-role key
#   TMS_ALLOW_LOCAL_API_BASE_URL   set to 1 to permit a loopback host (local/dev builds only)
#
# EXIT
#   0  every value is publishable
#   1  at least one value would produce a broken or misdirected bundle; the build must not run
#
# It never prints the value of VITE_SUPABASE_ANON_KEY, only a verdict about it.
set -euo pipefail

FAILURES=0

fail_check() {
  printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2
  FAILURES=$((FAILURES + 1))
}
ok_check() { printf '\033[1;32m[ok]\033[0m   %s\n' "$*"; }

ALLOW_LOCAL="${TMS_ALLOW_LOCAL_API_BASE_URL:-0}"

# ---------------------------------------------------------------------------------------------
# VITE_API_BASE_URL - the one whose absence is silent
# ---------------------------------------------------------------------------------------------
API_BASE_URL="${VITE_API_BASE_URL:-}"

if [ -z "$API_BASE_URL" ]; then
  fail_check "VITE_API_BASE_URL is unset or empty. env.ts would bake http://localhost:8080/api/v1
       into the bundle and the published frontend would call the visitor's own machine.
       Set it in the Amplify console (App settings -> Environment variables) and redeploy:
       a restart is not enough, because VITE_* values are compiled in at build time."
else
  case "$API_BASE_URL" in
    https://*) ok_check "VITE_API_BASE_URL uses https" ;;
    http://*)
      if [ "$ALLOW_LOCAL" = "1" ]; then
        ok_check "VITE_API_BASE_URL uses http, permitted by TMS_ALLOW_LOCAL_API_BASE_URL=1"
      else
        fail_check "VITE_API_BASE_URL is http://. Amplify serves the frontend over https, so the
       browser blocks every call to an http origin as mixed content. Use https, or set
       TMS_ALLOW_LOCAL_API_BASE_URL=1 for a local build."
      fi
      ;;
    *)
      fail_check "VITE_API_BASE_URL is not an absolute http(s) URL: '$API_BASE_URL'.
       env.ts only strips trailing slashes; it does not resolve a relative value."
      ;;
  esac

  case "$API_BASE_URL" in
    *localhost*|*127.0.0.1*|*0.0.0.0*|*'[::1]'*)
      if [ "$ALLOW_LOCAL" = "1" ]; then
        ok_check "VITE_API_BASE_URL points at a loopback host, permitted for a local build"
      else
        fail_check "VITE_API_BASE_URL points at a loopback host: '$API_BASE_URL'.
       That is precisely the value a browser on somebody else's machine cannot reach."
      fi
      ;;
  esac

  # The backend serves every business endpoint under tms.api.base-path, which is /api/v1 in
  # application.yml, and httpClient appends '/system/info', '/orders' and so on verbatim.
  case "${API_BASE_URL%/}" in
    */api/v1) ok_check "VITE_API_BASE_URL ends in /api/v1" ;;
    *)
      fail_check "VITE_API_BASE_URL does not end in /api/v1: '$API_BASE_URL'.
       The backend serves business endpoints under tms.api.base-path (/api/v1) and the client
       appends the endpoint path to this value unchanged."
      ;;
  esac
fi

# ---------------------------------------------------------------------------------------------
# Supabase Auth - absent means the login screen fails at the network layer, which is at least
# visible; a service-role key here would be a credential published to every visitor.
# ---------------------------------------------------------------------------------------------
SUPABASE_URL="${VITE_SUPABASE_URL:-}"
if [ -z "$SUPABASE_URL" ]; then
  fail_check "VITE_SUPABASE_URL is unset. The bundle would fall back to the local Supabase CLI
       default (http://localhost:54321) and nobody could sign in."
else
  case "$SUPABASE_URL" in
    *localhost*|*127.0.0.1*)
      if [ "$ALLOW_LOCAL" = "1" ]; then
        ok_check "VITE_SUPABASE_URL points at the local Supabase, permitted for a local build"
      else
        fail_check "VITE_SUPABASE_URL points at a loopback host: '$SUPABASE_URL'."
      fi
      ;;
    *) ok_check "VITE_SUPABASE_URL is set" ;;
  esac
fi

SUPABASE_KEY="${VITE_SUPABASE_ANON_KEY:-}"
if [ -z "$SUPABASE_KEY" ]; then
  fail_check "VITE_SUPABASE_ANON_KEY is unset. The bundle would carry the placeholder from
       env.ts and every sign-in would be rejected."
else
  # A Supabase service-role JWT carries the claim \"role\":\"service_role\"; its base64url
  # encoding is stable enough to catch the paste, and the check costs nothing.
  case "$SUPABASE_KEY" in
    *service_role*|*InNlcnZpY2Vfcm9sZSI*|sb_secret_*)
      fail_check "VITE_SUPABASE_ANON_KEY looks like a SERVICE-ROLE key. Every VITE_* value is
       readable by anyone who opens the bundle. Stop this build, rotate that key, and use the
       anon/publishable key."
      ;;
    *) ok_check "VITE_SUPABASE_ANON_KEY is set and is not a service-role key" ;;
  esac
fi

if [ "$FAILURES" -gt 0 ]; then
  printf '\n\033[1;31m[fail]\033[0m %s frontend environment check(s) failed. Not building.\n' "$FAILURES" >&2
  exit 1
fi

printf '\n\033[1;32m[ok]\033[0m   frontend environment is publishable.\n'
