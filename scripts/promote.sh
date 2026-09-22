#!/usr/bin/env bash
# Promotes one branch onto the next, fast-forward only, and tells you what the deploy will do.
#
# WHY THIS EXISTS
# ---------------
# The deployment mechanism of this system is *a push*. Render and Amplify each watch a branch
# configured in their own dashboards - `render.yaml` declares no `branch:` - so `git push
# origin dev:qas` is the entire deploy trigger. That makes the push the most consequential
# command in the project and, until this script, the least examined one: nothing printed which
# commit was about to become live, which migrations would run on boot, or whether the target
# branch had commits the source did not.
#
# So this refuses everything that is not a fast-forward, prints the revision that will be
# deployed and the forward-only migrations that will apply, and then stops. The push itself needs
# `--push`, typed by a person. Nothing here force-pushes, rewrites history or deletes anything.
#
# USAGE
#   scripts/promote.sh --from dev --to qas            # inspect; changes nothing
#   scripts/promote.sh --from dev --to qas --push     # actually promote
#
# EXIT
#   0  the promotion is a clean fast-forward (and was pushed, if --push was given)
#   1  it is not; nothing was pushed
set -euo pipefail

FROM=""
TO=""
DO_PUSH=0
REMOTE="origin"

while [ $# -gt 0 ]; do
  case "$1" in
    --from)   FROM="${2:-}"; shift 2 ;;
    --to)     TO="${2:-}"; shift 2 ;;
    --remote) REMOTE="${2:-}"; shift 2 ;;
    --push)   DO_PUSH=1; shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) printf 'unknown argument: %s (try --help)\n' "$1" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[ok]\033[0m   %s\n' "$*"; }
fail() { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

[ -n "$FROM" ] || fail "--from is required (the branch that has been validated)"
[ -n "$TO" ] || fail "--to is required (the branch the target environment deploys from)"

case "$TO" in
  qas|main) : ;;
  *) fail "--to must be 'qas' or 'main'. Those are the two branches an environment deploys from
       (docs/environments/QAS.md). Anything else is not a promotion." ;;
esac

# ---------------------------------------------------------------------------------------------
# A dirty tree means the thing you tested is not the thing you are about to promote.
# ---------------------------------------------------------------------------------------------
if [ -n "$(git status --porcelain)" ]; then
  fail "the working tree has uncommitted changes. Promote a committed revision, not a desk."
fi

log "fetching $REMOTE (read-only)"
git fetch --quiet "$REMOTE" || fail "could not fetch $REMOTE"

git rev-parse --verify --quiet "refs/heads/$FROM" >/dev/null \
  || fail "no local branch '$FROM'"

SOURCE_SHA="$(git rev-parse "$FROM")"
SOURCE_SHORT="$(git rev-parse --short=12 "$FROM")"

# The local source must itself be pushed, or the promotion would deploy a commit that exists on
# nobody else's machine.
if git rev-parse --verify --quiet "refs/remotes/$REMOTE/$FROM" >/dev/null; then
  if ! git merge-base --is-ancestor "$SOURCE_SHA" "$REMOTE/$FROM"; then
    fail "$FROM is ahead of $REMOTE/$FROM. Push $FROM first (a human decision), so that what
       becomes live is a revision the remote actually has."
  fi
  ok "$FROM is contained in $REMOTE/$FROM"
else
  fail "$REMOTE/$FROM does not exist. Push $FROM before promoting from it."
fi

# ---------------------------------------------------------------------------------------------
# Fast-forward only. If the target has commits the source does not, somebody committed straight
# onto the deployed branch, and silently overwriting that is exactly the accident this refuses.
# ---------------------------------------------------------------------------------------------
if git rev-parse --verify --quiet "refs/remotes/$REMOTE/$TO" >/dev/null; then
  TARGET_SHA="$(git rev-parse "$REMOTE/$TO")"
  TARGET_SHORT="$(git rev-parse --short=12 "$REMOTE/$TO")"

  if [ "$TARGET_SHA" = "$SOURCE_SHA" ]; then
    ok "$REMOTE/$TO is already at $SOURCE_SHORT. Nothing to promote, and no deploy would be
     triggered - a push with no new commit does not start a build."
    exit 0
  fi

  if ! git merge-base --is-ancestor "$TARGET_SHA" "$SOURCE_SHA"; then
    printf '\n'
    printf 'Commits on %s/%s that %s does not have:\n' "$REMOTE" "$TO" "$FROM"
    git --no-pager log --oneline "$SOURCE_SHA..$TARGET_SHA" || true
    printf '\n'
    fail "this is NOT a fast-forward. $REMOTE/$TO ($TARGET_SHORT) carries commits $FROM does not.
       Somebody committed onto the deployed branch. Bring them back into $FROM deliberately -
       never with --force, which would delete whatever is running in that environment from the
       history that records it."
  fi
  ok "fast-forward: $TARGET_SHORT -> $SOURCE_SHORT"
else
  TARGET_SHORT="(new branch)"
  ok "$REMOTE/$TO does not exist yet; this promotion creates it"
fi

# ---------------------------------------------------------------------------------------------
# What the deploy will actually do.
# ---------------------------------------------------------------------------------------------
printf '\n'
log "commits this promotion adds to $TO"
if [ "$TARGET_SHORT" = "(new branch)" ]; then
  git --no-pager log --oneline -n 20 "$SOURCE_SHA" || true
else
  git --no-pager log --oneline "$TARGET_SHA..$SOURCE_SHA" || true
fi

printf '\n'
log "Flyway migrations that will apply on the next boot (forward-only; there are no down-migrations)"
MIGRATION_DIR="backend/tms-api/src/main/resources/db/migration"
if [ "$TARGET_SHORT" = "(new branch)" ]; then
  printf '   (new branch: the whole history in %s applies to an empty schema)\n' "$MIGRATION_DIR"
else
  NEW_MIGRATIONS="$(git diff --name-only --diff-filter=A "$TARGET_SHA" "$SOURCE_SHA" -- "$MIGRATION_DIR" || true)"
  if [ -z "$NEW_MIGRATIONS" ]; then
    printf '   none - this promotion changes no schema\n'
  else
    printf '%s\n' "$NEW_MIGRATIONS" | sed 's#.*/#   + #'
  fi
  # An edited migration is the failure that stops the application from starting at all.
  EDITED="$(git diff --name-only --diff-filter=MRD "$TARGET_SHA" "$SOURCE_SHA" -- "$MIGRATION_DIR" || true)"
  if [ -n "$EDITED" ]; then
    printf '\n'
    log "migrations MODIFIED or REMOVED since $REMOTE/$TO - this is the blocking condition"
    printf '%s\n' "$EDITED" | sed 's#.*/#   ! #'
    fail "the migrations marked ! were MODIFIED or REMOVED relative to $REMOTE/$TO. Applied
       migrations are immutable (ADR-002): Flyway validates checksums on start, so this
       promotion would refuse to boot. Correct the schema with a NEW migration instead. Never
       run 'flyway repair' to silence it."
  fi
fi

printf '\n'
if [ "$DO_PUSH" != "1" ]; then
  log "DRY RUN - nothing was pushed"
  cat <<MSG

  To promote, run the same command with --push, or push by hand:

      git push $REMOTE $FROM:$TO

  Then, and only then, verify the environment - the push starts a build, it does not finish one:

      scripts/ops/verify-deployment.sh \\
        --api https://<$TO-api-host> \\
        --web https://<$TO-frontend-host> \\
        --commit $SOURCE_SHA

  The order matters and is documented in docs/operations/PROMOTION.md.
MSG
  exit 0
fi

log "pushing $FROM ($SOURCE_SHORT) to $REMOTE/$TO"
# No --force, no --force-with-lease, no refspec trickery: a plain fast-forward push, which the
# checks above have already proven this is. If the remote moved since the fetch, git refuses and
# that refusal is correct.
git push "$REMOTE" "$FROM:$TO"

cat <<MSG

$(ok "pushed. $TO is now at $SOURCE_SHORT")

The deploy is now RUNNING, not done. Render rebuilds the image and restarts; Amplify rebuilds the
bundle. Neither is finished when this command returns, and neither reports the thing that matters.

Verify before telling anyone the release is out:

    scripts/ops/verify-deployment.sh \\
      --api https://<$TO-api-host> \\
      --web https://<$TO-frontend-host> \\
      --commit $SOURCE_SHA

If it fails, see docs/operations/PROMOTION.md section 5 and
docs/operations/QAS_DEPLOYMENT_AND_RECOVERY.md section 6.
MSG
