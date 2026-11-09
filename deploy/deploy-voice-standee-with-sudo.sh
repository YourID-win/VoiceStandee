#!/usr/bin/env bash
# Deploys the staged build (see stage-voice-standee.sh) to /opt/voice-standee.
#
#   sudo bash deploy/deploy-voice-standee-with-sudo.sh --check-only            # read-only checks; prints the tree hash
#   sudo bash deploy/deploy-voice-standee-with-sudo.sh --confirm <tree-hash>   # the real deployment
#
# Needs the one-time setup first (deploy/setup-first-time-with-sudo.sh): the service user, the directories, the
# environment file with the bot token, and the systemd unit.
#
# What it does: copies the staged build next to the install, verifies the copy, stops the service, moves the
# current install to /opt/voice-standee.backup-<time> (kept), moves the new one into place, starts the service and
# verifies that it logged in to Discord and that the overlay web server answers. On any failure it stops the
# service, puts the previous install back and starts it again. The data directory (/var/lib/voice-standee: the
# registered pictures and settings) and the environment file are never touched.
#
# The very first installation (no /opt/voice-standee yet) has no previous version to go back to: on failure the
# service is simply left stopped.
#
# Error handling is explicit on purpose: every check ends in fail(), which exits, and an EXIT trap performs the
# rollback. It does not rely on "set -e", which bash silently disables in many calling contexts.
set -Euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_DIR="${INSTALL_DIR:-/opt/voice-standee}"
STAGE_DIR="${STAGE_DIR:-$ROOT/build/install/voice-standee}"
SERVICE="${SERVICE:-voice-standee.service}"
SERVICE_USER="${SERVICE_USER:-voice-standee}"
ENV_FILE="${ENV_FILE:-/etc/voice-standee/voice-standee.env}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8780/assets/overlay.css}"
STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-90}"

TS=""
BACKUP_DIR=""
NEW_DIR=""
FIRST_INSTALL=0
SERVICE_STOPPED=0
SWITCHED=0
COMPLETED=0
STAGED_HASH=""
STAGED_COMMIT=""

log() {
    printf '[voice-standee-deploy] %s\n' "$*"
}

fail() {
    log "ERROR: $*"
    exit 1
}

tree_hash() {
    (cd "$1" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')
}

set_owner() {
    chown -R "$SERVICE_USER:$SERVICE_USER" "$1"
}

service_active() {
    systemctl is-active --quiet "$SERVICE"
}

# ---- rollback (runs from the EXIT trap) -------------------------------------

rollback() {
    set +e
    log "Deployment failed. Rolling back."

    if (( SERVICE_STOPPED == 0 && SWITCHED == 0 )); then
        log "Nothing was changed; the service keeps running as before."
        if [[ -n "$NEW_DIR" && -d "$NEW_DIR" ]]; then
            log "A leftover copy remains at $NEW_DIR (safe to delete)."
        fi
        return 0
    fi

    systemctl stop "$SERVICE"
    if (( FIRST_INSTALL == 1 )); then
        log "This was the first installation, so there is no previous version. The service is left stopped."
        log "The failed build stays at $INSTALL_DIR. Check: journalctl -u $SERVICE -n 80"
        return 0
    fi
    if (( SWITCHED == 1 )) && [[ -d "$BACKUP_DIR" ]]; then
        mv -- "$INSTALL_DIR" "${INSTALL_DIR}.failed-${TS}"
        mv -- "$BACKUP_DIR" "$INSTALL_DIR"
        log "Previous install restored. The failed build is kept at ${INSTALL_DIR}.failed-${TS}"
    elif [[ -n "$NEW_DIR" && -d "$NEW_DIR" ]]; then
        log "A leftover copy remains at $NEW_DIR (safe to delete)."
    fi
    systemctl start "$SERVICE"
    sleep 5
    if service_active; then
        log "The previous version is running again."
    else
        log "WARNING: the service is NOT active after the rollback. Check: journalctl -u $SERVICE -n 80"
    fi
}

on_exit() {
    local status=$?
    trap - EXIT
    if (( COMPLETED == 0 && status != 0 )); then
        rollback
    fi
    exit "$status"
}

# ---- checks -------------------------------------------------------------------

preflight() {
    local command
    for command in systemctl journalctl curl find sha256sum awk cp mv df id; do
        command -v "$command" >/dev/null || fail "Required command is missing: $command"
    done

    id "$SERVICE_USER" >/dev/null 2>&1 || fail "The service user $SERVICE_USER does not exist (run deploy/setup-first-time-with-sudo.sh first)"
    systemctl cat "$SERVICE" >/dev/null 2>&1 || fail "$SERVICE is not installed (run deploy/setup-first-time-with-sudo.sh first)"
    [[ -r "$ENV_FILE" ]] || fail "The environment file is missing or unreadable: $ENV_FILE"
    if grep -Eq '^VOICE_STANDEE_TOKEN=(replace-with|$)' "$ENV_FILE"; then
        fail "The bot token is not set in $ENV_FILE yet (edit it with: sudoedit $ENV_FILE)"
    fi

    [[ -x "$STAGE_DIR/bin/voice-standee" ]] || fail "Staged build is missing (run deploy/stage-voice-standee.sh first): $STAGE_DIR"
    compgen -G "$STAGE_DIR/lib/voice-standee-*.jar" >/dev/null || fail "The program jar is missing from $STAGE_DIR/lib"

    STAGED_HASH="$(tree_hash "$STAGE_DIR")"
    [[ "$STAGED_HASH" =~ ^[0-9a-f]{64}$ ]] || fail "Could not compute the staged tree hash"
    local commit_file="${STAGE_DIR}.COMMIT"
    if [[ -r "$commit_file" ]]; then
        STAGED_COMMIT="$(cat "$commit_file")"
    else
        STAGED_COMMIT="unknown"
    fi
    log "Staged commit:    $STAGED_COMMIT"
    log "Staged tree hash: $STAGED_HASH"

    if [[ -d "$INSTALL_DIR" ]]; then
        local current_hash
        current_hash="$(tree_hash "$INSTALL_DIR")"
        log "Installed hash:   $current_hash"
        [[ "$STAGED_HASH" != "$current_hash" ]] || fail "The staged build is identical to what is installed; nothing to deploy"
        service_active || fail "$SERVICE is not active; refusing to deploy over a service that is not running"
    else
        FIRST_INSTALL=1
        log "Nothing is installed at $INSTALL_DIR yet: this will be the first installation."
    fi

    local free_kb
    free_kb="$(df --output=avail -k "$(dirname "$INSTALL_DIR")" | tail -n 1 | tr -d ' ')"
    [[ "$free_kb" =~ ^[0-9]+$ ]] && (( free_kb >= 204800 )) || fail "Less than 200 MB free next to $INSTALL_DIR"
}

verify_started() {
    local start_epoch="$1"
    local ready=0 attempt log_text

    for ((attempt = 1; attempt <= STARTUP_TIMEOUT_SECONDS; attempt++)); do
        service_active || fail "The service stopped after starting (see: journalctl -u $SERVICE --since @$start_epoch)"
        log_text="$(journalctl -u "$SERVICE" --since "@$start_epoch" --no-pager 2>/dev/null)"
        # "Ready as ..." is printed after the Discord login and after the overlay web server has started.
        if grep -Fq 'Login Successful!' <<<"$log_text" && grep -Fq 'Ready as ' <<<"$log_text"; then
            ready=1
            break
        fi
        sleep 1
    done
    [[ "$ready" == "1" ]] || fail "VoiceStandee did not finish starting (Discord login) within ${STARTUP_TIMEOUT_SECONDS}s"

    log_text="$(journalctl -u "$SERVICE" --since "@$start_epoch" --no-pager 2>/dev/null)"
    if grep -Eq 'SEVERE|NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|UnsatisfiedLinkError|Improper token|Invalid token|Failed to start' <<<"$log_text"; then
        fail "A startup failure was logged; see: journalctl -u $SERVICE --since @$start_epoch"
    fi

    local code
    code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$HEALTH_URL")"
    [[ "$code" == "200" ]] || fail "The overlay web server did not answer ($HEALTH_URL -> HTTP $code)"
    log "VoiceStandee is up (Discord login done), the overlay web server answers (HTTP $code)"
}

main() {
    local check_only=0
    local confirm=""

    if [[ "${1:-}" == "--check-only" ]]; then
        check_only=1
    elif [[ "${1:-}" == "--confirm" && "${2:-}" =~ ^[0-9a-f]{64}$ ]]; then
        confirm="$2"
    else
        fail "Run with --check-only, or --confirm <tree hash printed by --check-only>"
    fi

    trap on_exit EXIT
    preflight

    if (( check_only == 1 )); then
        COMPLETED=1
        log "Preflight passed. Nothing was changed. To deploy, run:"
        log "sudo bash deploy/deploy-voice-standee-with-sudo.sh --confirm $STAGED_HASH"
        return 0
    fi

    [[ "$confirm" == "$STAGED_HASH" ]] || fail "The staged build changed after approval (expected $confirm, found $STAGED_HASH)"
    if [[ "${REQUIRE_ROOT:-1}" == "1" ]]; then
        (( EUID == 0 )) || fail "The real deployment must run with sudo"
    fi
    umask 0022

    TS="$(date -u +%Y%m%d-%H%M%S)"
    BACKUP_DIR="${INSTALL_DIR}.backup-${TS}"
    NEW_DIR="${INSTALL_DIR}.new-${TS}"
    [[ ! -e "$BACKUP_DIR" && ! -e "$NEW_DIR" ]] || fail "A leftover directory for this timestamp already exists"

    log "Copying the staged build to $NEW_DIR"
    cp -a -- "$STAGE_DIR" "$NEW_DIR" || fail "Copying the staged build failed"
    set_owner "$NEW_DIR" || fail "Setting the owner of the new build failed"
    [[ "$(tree_hash "$NEW_DIR")" == "$STAGED_HASH" ]] || fail "The copied build does not match the staged build"

    if (( FIRST_INSTALL == 1 )); then
        SERVICE_STOPPED=1
        mv -- "$NEW_DIR" "$INSTALL_DIR" || fail "Moving the new build into place failed"
        SWITCHED=1
        log "Installed at $INSTALL_DIR"
    else
        log "Stopping $SERVICE"
        SERVICE_STOPPED=1
        systemctl stop "$SERVICE" || fail "Stopping the service failed"
        if service_active; then
            fail "The service did not stop"
        fi
        mv -- "$INSTALL_DIR" "$BACKUP_DIR" || fail "Moving the current install aside failed"
        SWITCHED=1
        mv -- "$NEW_DIR" "$INSTALL_DIR" || fail "Moving the new build into place failed"
        log "Switched. Previous install kept at $BACKUP_DIR"
    fi

    local start_epoch
    start_epoch="$(date +%s)"
    systemctl start "$SERVICE" || fail "Starting the service failed"
    verify_started "$start_epoch"

    COMPLETED=1
    log "Deployment completed successfully (commit $STAGED_COMMIT)"
    if (( FIRST_INSTALL == 0 )); then
        log "Backup of the previous version: $BACKUP_DIR"
    fi
    log "Next: in Discord, try /立ち絵 obs and open the address it shows."
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
