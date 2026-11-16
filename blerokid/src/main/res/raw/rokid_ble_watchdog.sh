# Adapted from Anezium/R08-Access-Bridge (Apache-2.0).
#!/system/bin/sh

VERSION="3"
PKG="${BLE_REMOTE_PACKAGE:-com.drift.bleremoterokid}"
SVC="${BLE_REMOTE_A11Y_SERVICE:-com.drift.bleremoterokid/com.drift.bleremoterokid.BleNavigationService}"
SCRIPT_NAME="rokid-ble-a11y-watchdog.sh"
PIDFILE="${BLE_REMOTE_A11Y_PIDFILE:-/data/local/tmp/rokid-ble-a11y-watchdog.pid}"
VERSIONFILE="${BLE_REMOTE_A11Y_VERSIONFILE:-/data/local/tmp/rokid-ble-a11y-watchdog.version}"
LOGFILE="${BLE_REMOTE_A11Y_LOGFILE:-/data/local/tmp/rokid-ble-a11y-watchdog.log}"
HEARTBEAT="${BLE_REMOTE_A11Y_HEARTBEAT:-/data/local/tmp/rokid-ble-a11y-watchdog.heartbeat}"
HEALTHY_SECONDS="${BLE_REMOTE_A11Y_HEALTHY_SECONDS:-30}"
RECOVERY_SECONDS="${BLE_REMOTE_A11Y_RECOVERY_SECONDS:-5}"
RECOVERY_CYCLES="${BLE_REMOTE_A11Y_RECOVERY_CYCLES:-3}"
LOG_HEALTHY_EVERY="${BLE_REMOTE_A11Y_LOG_HEALTHY_EVERY:-30}"
MAX_LOG_BYTES="${BLE_REMOTE_A11Y_MAX_LOG_BYTES:-65536}"

log_msg() {
    echo "$(date +%s) $*" >> "$LOGFILE"
}

rotate_log() {
    size="$(wc -c < "$LOGFILE" 2>/dev/null)"
    case "$size" in
        ''|*[!0-9]*) return ;;
    esac
    if [ "$size" -gt "$MAX_LOG_BYTES" ]; then
        tail -c 32768 "$LOGFILE" > "$LOGFILE.tmp" 2>/dev/null && mv "$LOGFILE.tmp" "$LOGFILE"
    fi
}

contains_service() {
    case ":$1:" in
        *":$SVC:"*) return 0 ;;
        *) return 1 ;;
    esac
}

is_alive() {
    pid="$1"
    [ -n "$pid" ] || return 1
    [ -d "/proc/$pid" ] || return 1
    # Read through cat: toybox tr spins forever on the read error of a process exiting meanwhile.
    cmdline="$(cat "/proc/$pid/cmdline" 2>/dev/null | tr '\000' ' ')"
    case "$cmdline" in
        *"$SCRIPT_NAME"*) return 0 ;;
        *) return 1 ;;
    esac
}

read_pid() {
    cat "$PIDFILE" 2>/dev/null
}

read_version() {
    cat "$VERSIONFILE" 2>/dev/null
}

app_pid() {
    pids="$(pidof "$PKG" 2>/dev/null)"
    echo "${pids%% *}"
}

repair_accessibility() {
    cur="$(settings get secure enabled_accessibility_services 2>/dev/null)"
    [ "$cur" = "null" ] && cur=""
    if [ -z "$cur" ]; then
        new="$SVC"
    elif contains_service "$cur"; then
        new="$cur"
    else
        new="$cur:$SVC"
    fi

    settings put secure enabled_accessibility_services "$new" >/dev/null 2>&1
    settings put secure accessibility_enabled 1 >/dev/null 2>&1

    # Clear Android's force-stopped flag without stealing the screen from the app
    # the user just opened. MainActivity + Home used to dismiss that app on FW 1.28.
    am start -n "$PKG/.RecoveryActivity" >/dev/null 2>&1 || true

    log_msg "repaired cur='$cur' new='$new' pid=$(app_pid)"
}

state_ok() {
    a11y_enabled="$(settings get secure accessibility_enabled 2>/dev/null)"
    enabled_services="$(settings get secure enabled_accessibility_services 2>/dev/null)"
    pid="$(app_pid)"
    reason=""
    [ "$a11y_enabled" = "1" ] || reason="${reason}a11y=$a11y_enabled "
    contains_service "$enabled_services" || reason="${reason}service_missing "
    [ -n "$pid" ] || reason="${reason}pid_missing "
    [ -z "$reason" ]
}

run_loop() {
    echo "$$" > "$PIDFILE"
    echo "$VERSION" > "$VERSIONFILE"
    log_msg "start pid=$$ version=$VERSION service=$SVC"
    tick=0
    recovery_left=0
    while true; do
        # Racing starters can each spawn a loop; only the PID-file owner stays.
        owner="$(read_pid)"
        if [ -n "$owner" ] && [ "$owner" != "$" ] && is_alive "$owner"; then
            log_msg "duplicate watchdog pid=$ exits for pid=$owner"
            exit 0
        fi
        tick=$((tick + 1))
        now="$(date +%s)"
        echo "$now" > "$HEARTBEAT"
        if state_ok; then
            if [ "$LOG_HEALTHY_EVERY" -gt 0 ] && [ $((tick % LOG_HEALTHY_EVERY)) -eq 0 ]; then
                log_msg "healthy tick=$tick pid=$pid"
                rotate_log
            fi
            if [ "$recovery_left" -gt 0 ]; then
                recovery_left=$((recovery_left - 1))
                sleep "$RECOVERY_SECONDS"
            else
                sleep "$HEALTHY_SECONDS"
            fi
        else
            log_msg "repair_needed tick=$tick reason='$reason' a11y='$a11y_enabled' pid='$pid' svcs='$enabled_services'"
            repair_accessibility
            rotate_log
            recovery_left="$RECOVERY_CYCLES"
            sleep "$RECOVERY_SECONDS"
        fi
    done
}

start_watchdog() {
    pid="$(read_pid)"
    running_version="$(read_version)"
    if is_alive "$pid" && [ "$running_version" = "$VERSION" ]; then
        echo "already running pid=$pid version=$running_version heartbeat=$(cat "$HEARTBEAT" 2>/dev/null)"
        exit 0
    fi
    if is_alive "$pid"; then
        kill "$pid" >/dev/null 2>&1 || true
        sleep 0.2
    fi
    rm -f "$PIDFILE"
    nohup sh "$0" run >/dev/null 2>&1 &
    sleep 0.5
    pid="$(read_pid)"
    if is_alive "$pid"; then
        echo "started pid=$pid version=$VERSION"
    else
        echo "failed to start"
        exit 1
    fi
}

stop_watchdog() {
    pid="$(read_pid)"
    if is_alive "$pid"; then
        kill "$pid" >/dev/null 2>&1 || true
        rm -f "$PIDFILE"
        log_msg "stopped pid=$pid"
        echo "stopped pid=$pid"
    else
        rm -f "$PIDFILE"
        echo "not running"
    fi
}

status_watchdog() {
    pid="$(read_pid)"
    if is_alive "$pid"; then
        echo "running pid=$pid version=$(read_version) heartbeat=$(cat "$HEARTBEAT" 2>/dev/null)"
    else
        echo "not running"
        exit 1
    fi
}

case "$1" in
    run)
        run_loop
        ;;
    start|"")
        start_watchdog
        ;;
    stop)
        stop_watchdog
        ;;
    restart)
        stop_watchdog >/dev/null 2>&1
        start_watchdog
        ;;
    status)
        status_watchdog
        ;;
    repair)
        repair_accessibility
        ;;
    *)
        echo "usage: $0 [start|stop|restart|status|repair]"
        exit 2
        ;;
esac
