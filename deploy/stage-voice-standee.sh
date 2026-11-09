#!/usr/bin/env bash
# Builds VoiceStandee from the CURRENT commit of this checkout and prints what the deployment script needs.
# Run as the normal user (no sudo). Does not touch /opt or the running service.
#
#   git fetch origin && git checkout main && git pull --ff-only
#   bash deploy/stage-voice-standee.sh
#
# Output: build/install/voice-standee/ and the "tree hash" that deploy-voice-standee-with-sudo.sh pins.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

log() { printf '[stage-voice-standee] %s\n' "$*"; }
fail() { printf '[stage-voice-standee] ERROR: %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null || fail "git is required"
[[ -z "$(git status --porcelain --untracked-files=no)" ]] \
    || fail "the working tree has uncommitted changes; commit or stash them first"

COMMIT="$(git rev-parse HEAD)"
BRANCH="$(git branch --show-current)"
log "building commit $COMMIT (branch: ${BRANCH:-detached})"

# tests are part of the build; a failing test stops the staging
./gradlew --no-daemon clean build installDist

STAGE="$ROOT/build/install/voice-standee"
[[ -x "$STAGE/bin/voice-standee" ]] || fail "installDist did not produce bin/voice-standee"
ls "$STAGE"/lib/voice-standee-*.jar >/dev/null || fail "the program jar is missing from lib/"
ls "$STAGE"/lib/jdave-native-linux-x86-64-*.jar >/dev/null || fail "the Linux voice-encryption library is missing from lib/"

tree_hash() {
    (cd "$1" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')
}
HASH="$(tree_hash "$STAGE")"
printf '%s\n' "$COMMIT" > "$ROOT/build/install/voice-standee.COMMIT"

log "done."
log "commit:    $COMMIT"
log "tree hash: $HASH"
log "next: sudo bash deploy/deploy-voice-standee-with-sudo.sh --check-only"
