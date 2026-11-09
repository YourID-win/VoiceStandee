#!/usr/bin/env bash
# One-time setup on the server: the service user, the directories, the environment file and the systemd unit.
# Safe to run again: it only creates what is missing and never overwrites the environment file.
#
#   sudo bash deploy/setup-first-time-with-sudo.sh --check-only   # shows what it would do, changes nothing
#   sudo bash deploy/setup-first-time-with-sudo.sh --apply        # does it
#
# It does NOT start the service and it never sees the bot token: after --apply you put the token into the
# environment file yourself (sudoedit /etc/voice-standee/voice-standee.env), then deploy the first build with
# deploy/deploy-voice-standee-with-sudo.sh.
set -Euo pipefail

SERVICE_USER="${SERVICE_USER:-voice-standee}"
DATA_DIR="${DATA_DIR:-/var/lib/voice-standee}"
CONFIG_DIR="${CONFIG_DIR:-/etc/voice-standee}"
ENV_FILE="${CONFIG_DIR}/voice-standee.env"
UNIT_FILE="${UNIT_FILE:-/etc/systemd/system/voice-standee.service}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UNIT_SOURCE="$ROOT/deploy/voice-standee.service.example"
ENV_SOURCE="$ROOT/config/voice-standee.env.example"

log() { printf '[voice-standee-setup] %s\n' "$*"; }
fail() { log "ERROR: $*"; exit 1; }

MODE="${1:-}"
[[ "$MODE" == "--check-only" || "$MODE" == "--apply" ]] || fail "Run with --check-only or --apply"
APPLY=0
[[ "$MODE" == "--apply" ]] && APPLY=1

[[ -r "$UNIT_SOURCE" ]] || fail "Missing $UNIT_SOURCE"
[[ -r "$ENV_SOURCE" ]] || fail "Missing $ENV_SOURCE"
if (( APPLY == 1 )) && [[ "${REQUIRE_ROOT:-1}" == "1" ]]; then
    (( EUID == 0 )) || fail "--apply must run with sudo"
fi
for command in useradd install systemctl id; do
    command -v "$command" >/dev/null || fail "Required command is missing: $command"
done

step() {
    # step "what it does" command...   (prints the step; runs it only with --apply)
    local what="$1"
    shift
    if (( APPLY == 1 )); then
        log "$what"
        "$@" || fail "Failed: $what"
    else
        log "would: $what"
    fi
}

if id "$SERVICE_USER" >/dev/null 2>&1; then
    log "user $SERVICE_USER exists"
else
    step "create the system user $SERVICE_USER (no login, no home)" \
        useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi

if [[ -d "$DATA_DIR" ]]; then
    log "data directory $DATA_DIR exists"
else
    step "create $DATA_DIR (pictures and settings) owned by $SERVICE_USER" \
        install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$DATA_DIR"
fi
if [[ -d "$DATA_DIR/tmp" ]]; then
    log "temp directory $DATA_DIR/tmp exists"
else
    step "create $DATA_DIR/tmp (where the voice-encryption library is unpacked)" \
        install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$DATA_DIR/tmp"
fi

if [[ -d "$CONFIG_DIR" ]]; then
    log "config directory $CONFIG_DIR exists"
else
    step "create $CONFIG_DIR" install -d -m 0750 -o root -g "$SERVICE_USER" "$CONFIG_DIR"
fi

if [[ -e "$ENV_FILE" ]]; then
    log "environment file $ENV_FILE exists (left as it is)"
else
    step "create $ENV_FILE from the example (readable by $SERVICE_USER only; the token is still a placeholder)" \
        install -m 0640 -o root -g "$SERVICE_USER" "$ENV_SOURCE" "$ENV_FILE"
fi

if [[ -e "$UNIT_FILE" ]] && cmp -s "$UNIT_SOURCE" "$UNIT_FILE"; then
    log "systemd unit is up to date"
else
    step "install the systemd unit $UNIT_FILE" install -m 0644 -o root -g root "$UNIT_SOURCE" "$UNIT_FILE"
    step "reload systemd" systemctl daemon-reload
fi
step "enable the service at boot (it is not started now)" systemctl enable voice-standee.service

if (( APPLY == 1 )); then
    log "Setup done. Next:"
    log "  1. sudoedit $ENV_FILE   (put the bot token in; keep the quotes off, no spaces)"
    log "  2. bash deploy/stage-voice-standee.sh  (as the normal user)"
    log "  3. sudo bash deploy/deploy-voice-standee-with-sudo.sh --check-only"
else
    log "Nothing was changed. To do it, run: sudo bash deploy/setup-first-time-with-sudo.sh --apply"
fi
