#!/usr/bin/env bash
# Stamps the frontend artefact with the commit it was built from.
#
# WHY THIS EXISTS
# ---------------
# Nothing in the published frontend says which commit it is. `dist/` is a hash-named bundle, the
# Amplify console shows a build id rather than a revision an engineer can `git show`, and the
# repository holds no URL from which either could be read. So "which build is live?" - the first
# question of every incident - had no answer at all.
#
# This writes `<dist>/release.json`, served as a plain static file alongside `index.html`, so the
# answer is one unauthenticated GET:
#
#     curl -fsS https://<frontend>/release.json
#
# It is deliberately a separate file rather than a value compiled into the bundle: writing it
# needs no change to `src/**` or to `vite.config.ts`, and an operator can read it without opening
# dev tools. The trade-off is that it is public - see "What this publishes" below.
#
# WHERE THE COMMIT COMES FROM
#   TMS_RELEASE_COMMIT   explicit override, for a build system not listed below
#   GITHUB_SHA           GitHub Actions
#   AWS_COMMIT_ID        AWS Amplify Hosting
#   RENDER_GIT_COMMIT    Render
#   git rev-parse HEAD   a working copy with history
#
# If none of them yields a value this script FAILS. A manifest that says "unknown" is worse than
# no manifest: it looks like an answer.
#
# USAGE
#   scripts/ci/write-release-manifest.sh [dist-dir]      (default: frontend/tms-web/dist)
#
# WHAT THIS PUBLISHES
# The commit id, the branch, the build time and the API base URL the bundle was compiled
# against. The API URL is already readable inside the bundle, and the branch and build time carry
# nothing sensitive. The commit id of a private repository is a fingerprint: it identifies the
# revision to anybody who already has the repository, and is opaque to anybody who does not. That
# is judged an acceptable price for being able to answer "what is live" without console access.
# Never add a variable to this file that is not already public.
set -euo pipefail

DIST_DIR="${1:-}"
if [ -z "$DIST_DIR" ]; then
  REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
  DIST_DIR="$REPO_ROOT/frontend/tms-web/dist"
fi

if [ ! -d "$DIST_DIR" ]; then
  printf '\033[1;31m[fail]\033[0m no such directory: %s (run the build first)\n' "$DIST_DIR" >&2
  exit 1
fi

resolve_commit() {
  if [ -n "${TMS_RELEASE_COMMIT:-}" ]; then printf '%s' "$TMS_RELEASE_COMMIT"; return 0; fi
  if [ -n "${GITHUB_SHA:-}" ];        then printf '%s' "$GITHUB_SHA";        return 0; fi
  if [ -n "${AWS_COMMIT_ID:-}" ];     then printf '%s' "$AWS_COMMIT_ID";     return 0; fi
  if [ -n "${RENDER_GIT_COMMIT:-}" ]; then printf '%s' "$RENDER_GIT_COMMIT"; return 0; fi
  git rev-parse HEAD 2>/dev/null || true
}

resolve_branch() {
  if [ -n "${TMS_RELEASE_BRANCH:-}" ]; then printf '%s' "$TMS_RELEASE_BRANCH"; return 0; fi
  if [ -n "${GITHUB_REF_NAME:-}" ];    then printf '%s' "$GITHUB_REF_NAME";    return 0; fi
  if [ -n "${AWS_BRANCH:-}" ];         then printf '%s' "$AWS_BRANCH";         return 0; fi
  if [ -n "${RENDER_GIT_BRANCH:-}" ];  then printf '%s' "$RENDER_GIT_BRANCH";  return 0; fi
  git rev-parse --abbrev-ref HEAD 2>/dev/null || true
}

COMMIT="$(resolve_commit)"
if [ -z "$COMMIT" ]; then
  cat >&2 <<'MSG'
[fail] Could not determine the commit this build came from.
       None of TMS_RELEASE_COMMIT, GITHUB_SHA, AWS_COMMIT_ID or RENDER_GIT_COMMIT is set and
       `git rev-parse HEAD` produced nothing (a shallow export has no history).
       Refusing to publish an artefact that cannot be traced back to a revision - set
       TMS_RELEASE_COMMIT explicitly if this build system is not one of the four.
MSG
  exit 1
fi

BRANCH="$(resolve_branch)"
SHORT_COMMIT="$(printf '%s' "$COMMIT" | cut -c1-12)"
BUILT_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
API_BASE_URL="${VITE_API_BASE_URL:-}"

# Minimal JSON escaping. Every value here is a SHA, a branch name, a URL or a timestamp, so a
# backslash or a quote would already be a sign that something is wrong - but the manifest must
# still be parseable rather than truncated.
json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

cat > "$DIST_DIR/release.json" <<JSON
{
  "application": "tms-web",
  "commit": "$(json_escape "$COMMIT")",
  "commitShort": "$(json_escape "$SHORT_COMMIT")",
  "branch": "$(json_escape "$BRANCH")",
  "builtAt": "$(json_escape "$BUILT_AT")",
  "apiBaseUrl": "$(json_escape "$API_BASE_URL")"
}
JSON

printf '\033[1;32m[ok]\033[0m   %s/release.json -> commit %s (%s)\n' "$DIST_DIR" "$SHORT_COMMIT" "${BRANCH:-detached}"
