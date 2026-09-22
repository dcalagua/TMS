#!/usr/bin/env bash
# Inspects the built frontend artefact before it is published.
#
# WHY THIS EXISTS
# ---------------
# `check-frontend-env.sh` reads the environment; this reads the *artefact*. They are not the same
# assurance. A variable can be present and still not reach the compiler - a typo in the name, a
# value scoped to the wrong Amplify branch, a cached `dist/` from an earlier build - and every one
# of those produces a bundle that looks fine and calls the wrong backend. The only place that
# question can be answered for certain is the bytes that are about to be uploaded.
#
# So this asserts two things about `dist/`:
#
#   1. the configured API base URL is physically present in the compiled JavaScript, which is
#      what proves the panel value was compiled in rather than defaulted; and
#   2. `release.json` names a commit, which is what makes the published build identifiable.
#
# Run it after `npm run build` and after `write-release-manifest.sh`.
#
# USAGE
#   scripts/ci/assert-frontend-bundle.sh [dist-dir]     (default: frontend/tms-web/dist)
#
# EXIT
#   0  the artefact is publishable
#   1  it is not; do not upload it
set -euo pipefail

DIST_DIR="${1:-}"
if [ -z "$DIST_DIR" ]; then
  REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
  DIST_DIR="$REPO_ROOT/frontend/tms-web/dist"
fi

FAILURES=0
fail_check() { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }
warn_check() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
ok_check()   { printf '\033[1;32m[ok]\033[0m   %s\n' "$*"; }

if [ ! -d "$DIST_DIR" ]; then
  fail_check "no such directory: $DIST_DIR (the build did not run, or wrote somewhere else)"
  exit 1
fi

# ---------------------------------------------------------------------------------------------
# 1. The document itself
# ---------------------------------------------------------------------------------------------
if [ -f "$DIST_DIR/index.html" ]; then
  ok_check "index.html is present"
else
  fail_check "index.html is missing from $DIST_DIR. There is no application to serve."
fi

# `find` rather than a glob: Vite writes hashed names under assets/, and an empty glob would
# otherwise be passed to grep as a literal path.
JS_FILES="$(find "$DIST_DIR" -type f -name '*.js' ! -name '*.map' 2>/dev/null || true)"
if [ -z "$JS_FILES" ]; then
  fail_check "no JavaScript was emitted under $DIST_DIR. The build produced no application code."
  JS_COUNT=0
else
  JS_COUNT="$(printf '%s\n' "$JS_FILES" | wc -l | tr -d ' ')"
  ok_check "$JS_COUNT JavaScript chunk(s) emitted"
fi

# ---------------------------------------------------------------------------------------------
# 2. The API base URL actually reached the compiler
# ---------------------------------------------------------------------------------------------
API_BASE_URL="${VITE_API_BASE_URL:-}"
ALLOW_LOCAL="${TMS_ALLOW_LOCAL_API_BASE_URL:-0}"

if [ -z "$API_BASE_URL" ]; then
  fail_check "VITE_API_BASE_URL is not set in this shell, so the artefact cannot be checked
       against it. env.ts's fallback means an unset variable produces a bundle that calls
       http://localhost:8080/api/v1 and says nothing about it. Set the variable and rebuild."
elif [ "$JS_COUNT" -gt 0 ]; then
  if printf '%s\n' "$JS_FILES" | xargs grep -Fl -- "$API_BASE_URL" >/dev/null 2>&1; then
    ok_check "the compiled bundle contains VITE_API_BASE_URL verbatim ($API_BASE_URL)"
  else
    fail_check "VITE_API_BASE_URL is '$API_BASE_URL' but that string appears in no compiled
       chunk. The value did not reach the build: check the variable name, and check that it is
       set for THIS Amplify branch rather than only for another one. Whatever this bundle calls,
       it is not the backend you configured."
  fi

  # Advisory, not a gate. `http://localhost:8080/api/v1` is a literal in env.ts, so it can
  # legitimately survive into the output as the unfolded right-hand side of `??` even on a
  # correctly configured build. Its presence is therefore evidence of nothing on its own; the
  # check above is the one that decides.
  if printf '%s\n' "$JS_FILES" | xargs grep -Fl -- 'http://localhost:8080' >/dev/null 2>&1; then
    if [ "$ALLOW_LOCAL" != "1" ]; then
      warn_check "the bundle still mentions http://localhost:8080. That is expected when the
       minifier does not fold env.ts's '??' fallback away, and is harmless as long as the check
       above passed. It is only alarming if that check FAILED."
    fi
  fi
fi

# ---------------------------------------------------------------------------------------------
# 3. The build is identifiable
# ---------------------------------------------------------------------------------------------
MANIFEST="$DIST_DIR/release.json"
if [ ! -f "$MANIFEST" ]; then
  fail_check "release.json is missing. Run scripts/ci/write-release-manifest.sh after the build;
       without it there is no way to tell which commit is live."
else
  # Deliberately grep rather than a JSON parser: jq is not guaranteed on an Amplify build image,
  # and this file is written by write-release-manifest.sh two lines above, not by a stranger.
  MANIFEST_COMMIT="$(sed -n 's/.*"commit"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
  case "$MANIFEST_COMMIT" in
    '' | unknown | null)
      fail_check "release.json carries no usable commit ('$MANIFEST_COMMIT'). A manifest that
       cannot name a revision is worse than none, because it looks like an answer."
      ;;
    *)
      ok_check "release.json names commit $MANIFEST_COMMIT"
      ;;
  esac
fi

if [ "$FAILURES" -gt 0 ]; then
  printf '\n\033[1;31m[fail]\033[0m %s artefact check(s) failed. Do not publish this build.\n' "$FAILURES" >&2
  exit 1
fi

printf '\n\033[1;32m[ok]\033[0m   the frontend artefact is publishable.\n'
