#!/usr/bin/env bash
# Post-deploy smoke for a TMS environment. The gate a promotion is allowed through.
#
# WHY THIS EXISTS
# ---------------
# Render reporting "live" means a container answered a health check. Amplify reporting "deployed"
# means files were uploaded. Neither says the schema migrated to the version this build expects,
# neither says the frontend is calling the right backend, and neither says a deep link survives a
# reload. Those three are what actually break, and until this script nothing checked any of them.
#
# Every check below fails the script. There is no "warn and continue": a promotion that keeps
# going after a failed smoke is not a gate.
#
# USAGE
#   scripts/ops/verify-deployment.sh --api https://<api-host> [options]
#
#   --api    <url>   REQUIRED. Origin of the backend, WITHOUT /api/v1 (the script appends both
#                    /actuator/... and /api/v1/... itself)
#   --web    <url>   Origin of the published frontend. Omitted, the frontend checks are skipped
#                    and the script says so
#   --commit <sha>   The commit this deployment is supposed to be running. Given, a mismatch is
#                    a failure - which is the whole point of stamping the SHA
#   --profile <name> Spring profile expected to be active (default: prod)
#   --timeout <secs> Per-request timeout (default: 20)
#
# EXIT
#   0  every check passed; the environment is serving the expected build
#   1  at least one check failed; do not promote further, and do not announce the release
#
# NOT VERIFIED: no TMS environment has ever been reached from this repository - there is no QAS
# URL committed anywhere, and no credential to obtain one. This script encodes what the code and
# the configuration say must be true; the first real run is expected to find something it does
# not mention.
set -euo pipefail

API_URL=""
WEB_URL=""
EXPECTED_COMMIT=""
EXPECTED_PROFILE="prod"
TIMEOUT=20

while [ $# -gt 0 ]; do
  case "$1" in
    --api)     API_URL="${2:-}"; shift 2 ;;
    --web)     WEB_URL="${2:-}"; shift 2 ;;
    --commit)  EXPECTED_COMMIT="${2:-}"; shift 2 ;;
    --profile) EXPECTED_PROFILE="${2:-}"; shift 2 ;;
    --timeout) TIMEOUT="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) printf 'unknown argument: %s (try --help)\n' "$1" >&2; exit 2 ;;
  esac
done

if [ -z "$API_URL" ]; then
  printf '\033[1;31m[fail]\033[0m --api is required. Example:\n' >&2
  printf '        scripts/ops/verify-deployment.sh --api https://tms-api.example --commit "$(git rev-parse HEAD)"\n' >&2
  exit 2
fi

command -v curl >/dev/null 2>&1 || { printf '[fail] curl is not on PATH\n' >&2; exit 2; }

API_URL="${API_URL%/}"
WEB_URL="${WEB_URL%/}"

FAILURES=0
BODY_FILE="$(mktemp -t tms-smoke.XXXXXX)"
trap 'rm -f "$BODY_FILE"' EXIT

fail_check() { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }
ok_check()   { printf '\033[1;32m[ok]\033[0m   %s\n' "$*"; }
skip_check() { printf '\033[1;33m[skip]\033[0m %s\n' "$*"; }
section()    { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

# Performs one GET, leaves the body in $BODY_FILE and echoes the status code. A transport
# failure (DNS, TLS, connection refused, timeout) yields 000, which no check accepts.
#
# `|| true` rather than `|| printf 000`: on a transport failure curl exits non-zero *and* has
# already written 000 through -w, so a fallback that printed its own would produce "000000" and
# every failure message would name a status code that does not exist.
http_get() {
  local code
  code="$(curl -sS -o "$BODY_FILE" -w '%{http_code}' \
    --max-time "$TIMEOUT" \
    -H "Accept: ${2:-application/json}" \
    "$1" 2>/dev/null || true)"
  printf '%s' "${code:-000}"
}

body_contains() { grep -Fq -- "$1" "$BODY_FILE"; }

json_field() {
  sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$BODY_FILE" | head -1
}

# =============================================================================================
# 1. MIGRATE + BOOT.  Readiness is UP only after Flyway has finished (spring.flyway.enabled is
#    true under prod with no variable to switch it off), so this single check is the migration
#    gate. A failed migration leaves the application refusing to start and this stays non-UP.
# =============================================================================================
section "1/6  Backend readiness - the migration gate"
STATUS="$(http_get "$API_URL/actuator/health/readiness")"
if [ "$STATUS" = "200" ] && body_contains '"status":"UP"'; then
  ok_check "/actuator/health/readiness is UP: the process started and Flyway completed"
else
  fail_check "/actuator/health/readiness returned HTTP $STATUS and did not report UP.
       This is the migration gate. Either the schema migration failed and the application
       refused to start (correct behaviour - read the deploy log for the Flyway error), or the
       database is unreachable. Nothing downstream of this is worth checking.
       Body: $(head -c 300 "$BODY_FILE" 2>/dev/null)"
fi

# =============================================================================================
# 2. LIVENESS. Separated on purpose: liveness UP with readiness DOWN means the JVM is running
#    and something below it is not - a different diagnosis from a container that never started.
# =============================================================================================
section "2/6  Backend liveness"
STATUS="$(http_get "$API_URL/actuator/health/liveness")"
if [ "$STATUS" = "200" ] && body_contains '"status":"UP"'; then
  ok_check "/actuator/health/liveness is UP"
else
  fail_check "/actuator/health/liveness returned HTTP $STATUS. The process itself is not healthy."
fi

# =============================================================================================
# 3. WHICH BUILD IS THIS.  /api/v1/system/info is the one intentionally public business endpoint
#    (PublicApiPaths.systemInfo), so this needs no credential.
# =============================================================================================
section "3/6  Release identity - which commit is answering"
STATUS="$(http_get "$API_URL/api/v1/system/info")"
if [ "$STATUS" != "200" ]; then
  fail_check "GET /api/v1/system/info returned HTTP $STATUS. It is public and unauthenticated,
       so anything other than 200 means the request never reached the application - check the
       host, the path prefix and whatever sits in front of it."
else
  ok_check "/api/v1/system/info answers"

  ACTUAL_PROFILES="$(sed -n 's/.*"profiles"[[:space:]]*:[[:space:]]*\[\([^]]*\)\].*/\1/p' "$BODY_FILE" | head -1)"
  case "$ACTUAL_PROFILES" in
    *"\"$EXPECTED_PROFILE\""*)
      ok_check "active profiles include '$EXPECTED_PROFILE'"
      ;;
    *)
      fail_check "active profiles are [$ACTUAL_PROFILES], which does not include
       '$EXPECTED_PROFILE'. Under any other profile the strict JWT rules, the closed
       documentation and the mandatory database variables are not in force."
      ;;
  esac

  ACTUAL_COMMIT="$(json_field commit)"
  if [ -z "$EXPECTED_COMMIT" ]; then
    if [ -n "$ACTUAL_COMMIT" ] && [ "$ACTUAL_COMMIT" != "unknown" ]; then
      skip_check "deployment reports commit '$ACTUAL_COMMIT'; nothing to compare it against
       (pass --commit \"\$(git rev-parse HEAD)\" to make this a gate)"
    else
      fail_check "the deployment reports no commit. Set TMS_RELEASE_COMMIT on the service, or
       deploy on a platform that sets RENDER_GIT_COMMIT, otherwise 'what is live' has no answer."
    fi
  else
    EXPECTED_SHORT="$(printf '%s' "$EXPECTED_COMMIT" | cut -c1-12)"
    ACTUAL_SHORT="$(printf '%s' "$ACTUAL_COMMIT" | cut -c1-12)"
    if [ -z "$ACTUAL_COMMIT" ] || [ "$ACTUAL_COMMIT" = "unknown" ]; then
      fail_check "expected commit $EXPECTED_SHORT but the deployment reports none. It cannot be
       confirmed that this is the build that was promoted."
    elif [ "$ACTUAL_SHORT" = "$EXPECTED_SHORT" ]; then
      ok_check "backend is running commit $ACTUAL_SHORT"
    else
      fail_check "COMMIT MISMATCH. Expected $EXPECTED_SHORT, the backend reports $ACTUAL_SHORT.
       The deploy did not pick up this revision - a queued build, a failed build that left the
       previous instance serving, or a push to the wrong branch."
    fi
  fi
fi

# =============================================================================================
# 4. THE SECURITY CHAIN IS CLOSED.  A smoke that only checks the public endpoint would pass just
#    as happily against a deployment whose authentication is not wired up.
# =============================================================================================
section "4/6  Business endpoints are not open"
STATUS="$(http_get "$API_URL/api/v1/orders")"
if [ "$STATUS" = "401" ]; then
  ok_check "/api/v1/orders refuses an anonymous caller with 401"
elif [ "$STATUS" = "403" ]; then
  ok_check "/api/v1/orders refuses an anonymous caller with 403"
else
  fail_check "/api/v1/orders returned HTTP $STATUS to a request carrying no token. It must be
       401 or 403. A 200 here is a security incident, not a smoke failure."
fi

# =============================================================================================
# 5 and 6. THE FRONTEND.
# =============================================================================================
if [ -z "$WEB_URL" ]; then
  section "5/6  Frontend"
  skip_check "--web was not given, so nothing about the published frontend was checked -
       including whether it is calling this backend and whether deep links survive a reload"
  section "6/6  SPA deep links"
  skip_check "--web was not given"
else
  section "5/6  Frontend release identity"
  STATUS="$(http_get "$WEB_URL/release.json")"
  if [ "$STATUS" != "200" ]; then
    fail_check "GET $WEB_URL/release.json returned HTTP $STATUS. The build was published without
       a release manifest, so which commit is live cannot be read from the frontend. Add
       scripts/ci/write-release-manifest.sh to the Amplify build phase (see
       docs/operations/PROMOTION.md)."
  else
    WEB_COMMIT="$(json_field commit)"
    WEB_API="$(json_field apiBaseUrl)"
    ok_check "release.json reports commit ${WEB_COMMIT:-<none>} against API ${WEB_API:-<none>}"

    if [ -n "$EXPECTED_COMMIT" ]; then
      if [ "$(printf '%s' "$WEB_COMMIT" | cut -c1-12)" = "$(printf '%s' "$EXPECTED_COMMIT" | cut -c1-12)" ]; then
        ok_check "frontend is running the expected commit"
      else
        fail_check "FRONTEND COMMIT MISMATCH. Expected $(printf '%s' "$EXPECTED_COMMIT" | cut -c1-12),
       published bundle reports $(printf '%s' "$WEB_COMMIT" | cut -c1-12). Backend and frontend are
       deployed by two independent channels and they have drifted."
      fi
    fi

    case "$WEB_API" in
      "")
        fail_check "release.json records no apiBaseUrl, so the bundle was built with
       VITE_API_BASE_URL unset and is calling http://localhost:8080/api/v1 from every visitor's
       browser. Set the variable in the Amplify console and REBUILD - a restart will not change
       a compiled-in value."
        ;;
      *localhost*|*127.0.0.1*)
        # Checked before the "matches this backend" case on purpose: a published bundle pointing
        # at loopback is fatal even when it happens to match the host being smoked, because what
        # it matches is the operator's own machine. TMS_ALLOW_LOCAL_API_BASE_URL=1 - the same
        # escape scripts/ci/check-frontend-env.sh honours - exists so this script can be
        # rehearsed against a local stack without softening what it does against a real one.
        if [ "${TMS_ALLOW_LOCAL_API_BASE_URL:-0}" = "1" ]; then
          ok_check "the bundle targets a loopback API ($WEB_API), permitted by
       TMS_ALLOW_LOCAL_API_BASE_URL=1 - never set this against a real environment"
        else
          fail_check "the published bundle was compiled against '$WEB_API'. Every business call
       goes to the visitor's own machine. Set VITE_API_BASE_URL in the Amplify console and
       rebuild - a restart cannot change a compiled-in value."
        fi
        ;;
      "$API_URL/api/v1")
        ok_check "the bundle is compiled against this backend ($WEB_API)"
        ;;
      *)
        fail_check "the bundle is compiled against '$WEB_API', which is not '$API_URL/api/v1' -
       the backend this smoke just checked. One of the two is pointed at the wrong environment."
        ;;
    esac
  fi

  section "6/6  SPA deep links - the rewrite rule"
  # Single-quoted so nothing here is a shell expansion, and kept byte-identical to
  # docs/operations/PROMOTION.md section 4.1 - a divergent second copy of a rule an operator
  # pastes into a console is worse than no copy.
  REWRITE_SOURCE='</^[^.]+$|\.(?!(css|gif|ico|jpg|jpeg|js|json|map|png|txt|svg|webp|avif|woff|woff2|ttf|eot|xml|webmanifest)$)([^.]+$)/>'
  # /masters/locations exists only inside the React router. Without the console rewrite rule
  # (see docs/operations/PROMOTION.md) the host answers 404 for it, the application works only
  # from the home page, and every shared link and every reload breaks. 24 navigation E2E
  # assertions encode exactly this.
  for DEEP_PATH in /masters/locations /orders /esta-ruta-no-existe; do
    STATUS="$(http_get "$WEB_URL$DEEP_PATH" 'text/html')"
    if [ "$STATUS" = "200" ] && body_contains '<div id="root">'; then
      ok_check "$DEEP_PATH serves index.html"
    else
      fail_check "$WEB_URL$DEEP_PATH returned HTTP $STATUS and did not serve the application
       document. The SPA rewrite rule is missing. It cannot be declared in amplify.yml; add it in
       the Amplify console: Hosting -> Rewrites and redirects, target /index.html, type 200
       (Rewrite), source (kept identical to docs/operations/PROMOTION.md section 4.1, which
       explains why 'json' and 'map' must stay in the list):
       $REWRITE_SOURCE"
    fi
  done
fi

printf '\n'
if [ "$FAILURES" -gt 0 ]; then
  printf '\033[1;31m[fail]\033[0m %s check(s) failed. This deployment must not be promoted further.\n' "$FAILURES" >&2
  exit 1
fi
printf '\033[1;32m[ok]\033[0m   every check passed.\n'
