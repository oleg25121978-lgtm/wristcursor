#!/system/bin/sh

PKG="com.drift.bleremoterokid"
SVC="com.drift.bleremoterokid/com.drift.bleremoterokid.BleNavigationService"
RECOVERY="$PKG/.RecoveryActivity"

PIDFILE="/data/local/tmp/rokid-ble-watchdog.pid"
LOGFILE="/data/local/tmp/rokid-ble-watchdog.log"
HEARTBEAT="/sdcard/Android/data/$PKG/files/watchdog.heartbeat"
POLL_SECONDS=1

mkdir -p "/sdcard/Android/data/$PKG/files" >/dev/null 2>&1 || true

log_msg() {
    echo "$(date +%s) $*" >> "$LOGFILE"
}

contains_service() {
    case ":$1:" in
        *":$SVC:"*) return 0 ;;
        *) return 1 ;;
    esac
}

is_watchdog_alive() {
    p="$1"
    [ -n "$p" ] || return 1
    [ -d "/proc/$p" ] || return 1
    cmd="$(cat "/proc/$p/cmdline" 2>/dev/null | tr '\000' ' ')"
    case "$cmd" in
        *"rokid_ble_watchdog.sh"*) return 0 ;;
        *) return 1 ;;
    esac
}

app_pid() {
    pids="$(pidof "$PKG" 2>/dev/null)"
    echo "${pids%% *}"
}

repair() {
    cur="$(settings get secure enabled_accessibility_services 2>/dev/null)"
    [ "$cur" = "null" ] && cur=""

    if [ -z "$cur" ]; then
        new="$SVC"
    elif contains_service "$cur"; then
        new="$cur"
    else
        new="$cur:$SVC"
    fi

    settings put secure enabled_accessibility_services "$new" >/dev/null 2>&1 || true
    settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true

    # Starting an activity clears Android's force-stopped package flag.
    # RecoveryActivity immediately restarts the BLE foreground service and closes.
    am start -W -n "$RECOVERY" >/dev/null 2>&1 || true

    log_msg "repair cur='$cur' new='$new' pid=$(app_pid)"
}

healthy() {
    a11y="$(settings get secure accessibility_enabled 2>/dev/null)"
    svcs="$(settings get secure enabled_accessibility_services 2>/dev/null)"
    pid="$(app_pid)"
    [ "$a11y" = "1" ] || return 1
    contains_service "$svcs" || return 1
    [ -n "$pid" ] || return 1
    return 0
}

run_loop() {
    echo "$$" > "$PIDFILE"
    chmod 666 "$HEARTBEAT" >/dev/null 2>&1 || true
    log_msg "watchdog start pid=$$"

    while true; do
        owner="$(cat "$PIDFILE" 2>/dev/null)"
        if [ -n "$owner" ] && [ "$owner" != "$$" ] && is_watchdog_alive "$owner"; then
            exit 0
        fi

        date +%s > "$HEARTBEAT" 2>/dev/null || true
        chmod 666 "$HEARTBEAT" >/dev/null 2>&1 || true

        if ! healthy; then
            repair
        fi
        sleep "$POLL_SECONDS"
    done
}

start_watchdog() {
    old="$(cat "$PIDFILE" 2>/dev/null)"
    if is_watchdog_alive "$old"; then
        echo "already running pid=$old"
        exit 0
    fi
    rm -f "$PIDFILE"
    nohup sh "$0" run >/dev/null 2>&1 &
    sleep 0.4
    p="$(cat "$PIDFILE" 2>/dev/null)"
    if is_watchdog_alive "$p"; then
        echo "started pid=$p"
    else
        echo "failed"
        exit 1
    fi
}

stop_watchdog() {
    p="$(cat "$PIDFILE" 2>/dev/null)"
    if is_watchdog_alive "$p"; then
        kill "$p" >/dev/null 2>&1 || true
    fi
    rm -f "$PIDFILE"
    echo "stopped"
}

case "$1" in
    run) run_loop ;;
    start|"") start_watchdog ;;
    stop) stop_watchdog ;;
    restart) stop_watchdog >/dev/null 2>&1; start_watchdog ;;
    repair) repair ;;
    status)
        p="$(cat "$PIDFILE" 2>/dev/null)"
        if is_watchdog_alive "$p"; then
            echo "running pid=$p heartbeat=$(cat "$HEARTBEAT" 2>/dev/null)"
        else
            echo "not running"
            exit 1
        fi
        ;;
    *) echo "usage: $0 [start|stop|restart|repair|status]"; exit 2 ;;
esac
