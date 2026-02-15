#!/usr/bin/env bash
#
# Launch any command as a detached background process with PID/log tracking.
#
# This wrapper is intended for debugger target JVM launches so agent sessions
# cannot accidentally kill the process by closing a PTY.
#
# Example:
#   scripts/launch-detached.sh \
#     --name myapp-debug-target \
#     --wait-port 5005 \
#     -- java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
#        -jar your-application.jar
#

set -euo pipefail

NAME="debug-target"
LOG_FILE=""
PID_FILE=""
WAIT_PORT=""
WAIT_HOST="127.0.0.1"
WAIT_TIMEOUT_SEC=30
WAIT_INTERVAL_SEC=0.2
WORKDIR=""
REPLACE=false

usage() {
  cat <<'EOF'
Usage:
  scripts/launch-detached.sh [options] -- <command> [args...]

Options:
  --name <name>                Logical name used for default PID/log paths
                               (default: debug-target)
  --log-file <path>            Log file path (default: logs/<name>.log)
  --pid-file <path>            PID file path (default: .pids/<name>.pid)
  --wait-port <port>           Wait until this TCP port is reachable
  --wait-host <host>           Host used with --wait-port (default: 127.0.0.1)
  --wait-timeout-sec <sec>     Max wait time for --wait-port (default: 30)
  --wait-interval-sec <sec>    Poll interval for --wait-port (default: 0.2)
  --cwd <dir>                  Working directory for the launched command
  --replace                    Stop existing PID from pid file before launch
  -h, --help                   Show this help

Notes:
  - Command is launched via nohup with stdin detached from terminal.
  - Existing process in pid file causes failure unless --replace is passed.
EOF
}

fail() {
  echo "launch-detached: $*" >&2
  exit 1
}

require_value() {
  local flag="$1"
  local value="${2:-}"
  [[ -n "$value" ]] || fail "missing value for $flag"
}

is_positive_int() {
  [[ "$1" =~ ^[0-9]+$ ]] && [[ "$1" -gt 0 ]]
}

abs_path() {
  local value="$1"
  if [[ "$value" == /* ]]; then
    printf '%s\n' "$value"
  else
    printf '%s/%s\n' "$(pwd)" "$value"
  fi
}

is_local_host() {
  local host="$1"
  [[ "$host" == "127.0.0.1" || "$host" == "localhost" || "$host" == "0.0.0.0" || "$host" == "::1" ]]
}

check_port_ready() {
  if command -v nc >/dev/null 2>&1; then
    nc -z "$WAIT_HOST" "$WAIT_PORT" >/dev/null 2>&1 && return 0
  fi

  if command -v lsof >/dev/null 2>&1 && is_local_host "$WAIT_HOST"; then
    lsof -nP -iTCP:"$WAIT_PORT" -sTCP:LISTEN >/dev/null 2>&1 && return 0
  fi

  return 1
}

stop_existing_pid() {
  local pid="$1"
  kill "$pid" >/dev/null 2>&1 || true

  local attempts=50
  local i
  for i in $(seq 1 "$attempts"); do
    if ! ps -p "$pid" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
  done

  fail "existing process $pid did not stop after SIGTERM; stop it manually"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name)
      require_value "$1" "${2:-}"
      NAME="$2"
      shift 2
      ;;
    --log-file)
      require_value "$1" "${2:-}"
      LOG_FILE="$2"
      shift 2
      ;;
    --pid-file)
      require_value "$1" "${2:-}"
      PID_FILE="$2"
      shift 2
      ;;
    --wait-port)
      require_value "$1" "${2:-}"
      WAIT_PORT="$2"
      shift 2
      ;;
    --wait-host)
      require_value "$1" "${2:-}"
      WAIT_HOST="$2"
      shift 2
      ;;
    --wait-timeout-sec)
      require_value "$1" "${2:-}"
      WAIT_TIMEOUT_SEC="$2"
      shift 2
      ;;
    --wait-interval-sec)
      require_value "$1" "${2:-}"
      WAIT_INTERVAL_SEC="$2"
      shift 2
      ;;
    --cwd)
      require_value "$1" "${2:-}"
      WORKDIR="$2"
      shift 2
      ;;
    --replace)
      REPLACE=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    *)
      fail "unknown option: $1 (use --help)"
      ;;
  esac
done

[[ $# -gt 0 ]] || fail "missing command; use -- <command> [args...]"

if [[ -n "$WAIT_PORT" ]] && ! is_positive_int "$WAIT_PORT"; then
  fail "--wait-port must be a positive integer"
fi
if ! is_positive_int "$WAIT_TIMEOUT_SEC"; then
  fail "--wait-timeout-sec must be a positive integer"
fi
if [[ -n "$WORKDIR" && ! -d "$WORKDIR" ]]; then
  fail "--cwd directory does not exist: $WORKDIR"
fi

if [[ -z "$LOG_FILE" ]]; then
  LOG_FILE="logs/${NAME}.log"
fi
if [[ -z "$PID_FILE" ]]; then
  PID_FILE=".pids/${NAME}.pid"
fi

LOG_FILE="$(abs_path "$LOG_FILE")"
PID_FILE="$(abs_path "$PID_FILE")"

mkdir -p "$(dirname "$LOG_FILE")" "$(dirname "$PID_FILE")"

if [[ -f "$PID_FILE" ]]; then
  existing_pid="$(tr -d '[:space:]' < "$PID_FILE" || true)"
  if [[ -n "$existing_pid" ]] && ps -p "$existing_pid" >/dev/null 2>&1; then
    if [[ "$REPLACE" == "true" ]]; then
      stop_existing_pid "$existing_pid"
    else
      fail "process already running with PID $existing_pid (use --replace to stop it)"
    fi
  fi
fi

if [[ -n "$WORKDIR" ]]; then
  launched_pid="$(
    cd "$WORKDIR"
    nohup "$@" >"$LOG_FILE" 2>&1 < /dev/null &
    echo "$!"
  )"
else
  nohup "$@" >"$LOG_FILE" 2>&1 < /dev/null &
  launched_pid="$!"
fi

echo "$launched_pid" > "$PID_FILE"

sleep 0.1
if ! ps -p "$launched_pid" >/dev/null 2>&1; then
  echo "launch-detached: process exited during startup (PID $launched_pid)" >&2
  if [[ -f "$LOG_FILE" ]]; then
    echo "launch-detached: last log lines from $LOG_FILE:" >&2
    tail -n 40 "$LOG_FILE" >&2 || true
  fi
  exit 1
fi

if [[ -n "$WAIT_PORT" ]]; then
  start_ts="$(date +%s)"
  while true; do
    if ! ps -p "$launched_pid" >/dev/null 2>&1; then
      echo "launch-detached: process exited while waiting for $WAIT_HOST:$WAIT_PORT" >&2
      if [[ -f "$LOG_FILE" ]]; then
        echo "launch-detached: last log lines from $LOG_FILE:" >&2
        tail -n 40 "$LOG_FILE" >&2 || true
      fi
      exit 1
    fi

    if check_port_ready; then
      break
    fi

    now_ts="$(date +%s)"
    if (( now_ts - start_ts >= WAIT_TIMEOUT_SEC )); then
      fail "timed out waiting for $WAIT_HOST:$WAIT_PORT after ${WAIT_TIMEOUT_SEC}s"
    fi
    sleep "$WAIT_INTERVAL_SEC"
  done
fi

echo "Detached process started"
echo "  name:      $NAME"
echo "  pid:       $launched_pid"
echo "  pid_file:  $PID_FILE"
echo "  log_file:  $LOG_FILE"
if [[ -n "$WAIT_PORT" ]]; then
  echo "  jdwp:      $WAIT_HOST:$WAIT_PORT (ready)"
fi
if [[ -n "$WORKDIR" ]]; then
  echo "  cwd:       $WORKDIR"
fi
