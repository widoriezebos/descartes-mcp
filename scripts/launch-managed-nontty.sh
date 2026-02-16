#!/usr/bin/env bash
#
# Launch a command as a supervised child process using non-TTY pipes.
#
# This script is intended for JDWP debug targets where agents must keep the
# process alive without binding lifecycle to a PTY terminal.
#

set -euo pipefail

NAME="debug-target"
PID_FILE=""
WORKDIR=""
JSON_OUTPUT=false
USE_SETSID=false
CHILD_PID=""
STARTED_AT_EPOCH_MS=0

usage() {
  cat <<'EOF'
Usage:
  scripts/launch-managed-nontty.sh [options] -- <command> [args...]

Options:
  --name <name>      Logical name used for default PID path (default: debug-target)
  --pid-file <path>  PID file path (default: .pids/<name>.pid)
  --cwd <dir>        Working directory for the launched command
  --json             Emit machine-readable JSON metadata on start
  -h, --help         Show this help

Behavior:
  - Requires stdin/stdout/stderr to be non-TTY.
  - Starts a supervised child process (no nohup, no detach).
  - Forwards termination signals to the child.
  - Exits with the same exit code as the child process.
EOF
}

fail() {
  echo "launch-managed-nontty: $*" >&2
  exit 1
}

require_value() {
  local flag="$1"
  local value="${2:-}"
  [[ -n "$value" ]] || fail "missing value for $flag"
}

abs_path() {
  local value="$1"
  if [[ "$value" == /* ]]; then
    printf '%s\n' "$value"
  else
    printf '%s/%s\n' "$(pwd)" "$value"
  fi
}

json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

forward_signal() {
  local signal_name="$1"

  if [[ -z "$CHILD_PID" ]]; then
    return 0
  fi
  if ! kill -0 "$CHILD_PID" >/dev/null 2>&1; then
    return 0
  fi

  if [[ "$USE_SETSID" == "true" ]]; then
    kill "-$signal_name" "-$CHILD_PID" >/dev/null 2>&1 || kill "-$signal_name" "$CHILD_PID" >/dev/null 2>&1 || true
  else
    kill "-$signal_name" "$CHILD_PID" >/dev/null 2>&1 || true
  fi
}

wait_for_child() {
  local child_pid="$1"
  local status

  while true; do
    set +e
    wait "$child_pid"
    status=$?
    set -e

    if (( status >= 128 )) && kill -0 "$child_pid" >/dev/null 2>&1; then
      continue
    fi

    return "$status"
  done
}

emit_started() {
  local cwd_value="$1"

  if [[ "$JSON_OUTPUT" == "true" ]]; then
    local cwd_json="null"
    if [[ -n "$cwd_value" ]]; then
      cwd_json="\"$(json_escape "$cwd_value")\""
    fi
    printf '{'
    printf '"name":"%s",' "$(json_escape "$NAME")"
    printf '"pid":%s,' "$CHILD_PID"
    printf '"pid_file":"%s",' "$(json_escape "$PID_FILE")"
    printf '"cwd":%s,' "$cwd_json"
    printf '"started_at_epoch_ms":%s' "$STARTED_AT_EPOCH_MS"
    printf '}\n'
    return 0
  fi

  echo "Managed non-TTY process started"
  echo "  name:      $NAME"
  echo "  pid:       $CHILD_PID"
  echo "  pid_file:  $PID_FILE"
  if [[ -n "$cwd_value" ]]; then
    echo "  cwd:       $cwd_value"
  fi
  echo "  mode:      supervised child (no detach)"
}

cleanup() {
  if [[ -n "$PID_FILE" ]]; then
    rm -f "$PID_FILE"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name)
      require_value "$1" "${2:-}"
      NAME="$2"
      shift 2
      ;;
    --pid-file)
      require_value "$1" "${2:-}"
      PID_FILE="$2"
      shift 2
      ;;
    --cwd)
      require_value "$1" "${2:-}"
      WORKDIR="$2"
      shift 2
      ;;
    --json)
      JSON_OUTPUT=true
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

if [[ -t 0 || -t 1 || -t 2 ]]; then
  fail "stdin/stdout/stderr must all be non-TTY (launch with tty=false)"
fi

if [[ -n "$WORKDIR" && ! -d "$WORKDIR" ]]; then
  fail "--cwd directory does not exist: $WORKDIR"
fi

if [[ -z "$PID_FILE" ]]; then
  PID_FILE=".pids/${NAME}.pid"
fi

PID_FILE="$(abs_path "$PID_FILE")"
mkdir -p "$(dirname "$PID_FILE")"

if [[ -f "$PID_FILE" ]]; then
  existing_pid="$(tr -d '[:space:]' < "$PID_FILE" || true)"
  if [[ -n "$existing_pid" ]] && kill -0 "$existing_pid" >/dev/null 2>&1; then
    fail "pid file already points to running process $existing_pid; stop it first"
  fi
fi

if command -v setsid >/dev/null 2>&1; then
  USE_SETSID=true
fi

trap 'forward_signal TERM' TERM
trap 'forward_signal INT' INT
trap 'forward_signal HUP' HUP
trap 'forward_signal QUIT' QUIT
trap cleanup EXIT

ORIGINAL_DIR="$(pwd)"
if [[ -n "$WORKDIR" ]]; then
  cd "$WORKDIR"
fi

if [[ "$USE_SETSID" == "true" ]]; then
  setsid "$@" &
else
  "$@" &
fi
CHILD_PID="$!"
STARTED_AT_EPOCH_MS="$(( $(date +%s) * 1000 ))"
echo "$CHILD_PID" > "$PID_FILE"

if [[ -n "$WORKDIR" ]]; then
  cd "$ORIGINAL_DIR"
fi

sleep 0.05
if ! kill -0 "$CHILD_PID" >/dev/null 2>&1; then
  fail "process exited during startup (pid=$CHILD_PID)"
fi

emit_started "$WORKDIR"
wait_for_child "$CHILD_PID"
exit $?
