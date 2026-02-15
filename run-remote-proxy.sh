#!/bin/bash
#
# Start Descartes remote debug proxy in the current terminal.
#
# Default target:
#   JDWP: localhost:5005
#   MCP:  localhost:9090
#
# Usage:
#   ./run-remote-proxy.sh
#   ./run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
#   ./run-remote-proxy.sh --auto-discover
#   ./run-remote-proxy.sh --log-file logs/descartes-proxy.log --auto-discover
#   ./run-remote-proxy.sh --rebuild
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if PROJECT_ROOT_CANDIDATE="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null)"; then
    PROJECT_ROOT="$PROJECT_ROOT_CANDIDATE"
else
    PROJECT_ROOT="$SCRIPT_DIR"
fi

LOG_FILE="${DESCARTES_PROXY_LOG_FILE:-$PROJECT_ROOT/logs/descartes-proxy.log}"
FORCE_REBUILD="${DESCARTES_PROXY_FORCE_REBUILD:-0}"
ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --log-file)
            if [[ $# -lt 2 ]]; then
                echo "Missing value for --log-file" >&2
                exit 1
            fi
            LOG_FILE="$2"
            shift 2
            ;;
        --rebuild)
            FORCE_REBUILD="1"
            shift
            ;;
        *)
            ARGS+=("$1")
            shift
            ;;
    esac
done

if [[ "$LOG_FILE" != /* ]]; then
    LOG_FILE="$PROJECT_ROOT/$LOG_FILE"
fi
mkdir -p "$(dirname "$LOG_FILE")"
touch "$LOG_FILE"

# Mirror all script/proxy output to terminal and log file.
exec > >(tee -a "$LOG_FILE") 2>&1

echo "Descartes proxy log file: $LOG_FILE"

if [ "${#ARGS[@]}" -eq 0 ]; then
    ARGS=(--jdwp-host localhost --jdwp-port 5005 --mcp-port 9090)
fi

echo "Starting Descartes remote proxy:"
echo "  repo: $PROJECT_ROOT"
echo "  args: ${ARGS[*]}"

cd "$PROJECT_ROOT"

MAIN_JAR=$(ls -t target/descartes-mcp-*-jar-with-dependencies.jar 2>/dev/null | head -n1 || true)
NEEDS_BUILD=0
if [ -z "${MAIN_JAR:-}" ]; then
    NEEDS_BUILD=1
elif [ "$FORCE_REBUILD" = "1" ]; then
    NEEDS_BUILD=1
elif find src/main/java src/main/resources pom.xml -newer "$MAIN_JAR" -print -quit 2>/dev/null | grep -q .; then
    NEEDS_BUILD=1
fi

if [ "$NEEDS_BUILD" = "1" ]; then
    echo "Building Descartes shaded JAR..." >&2
    mvn clean package -DskipTests -q
    MAIN_JAR=$(ls -t target/descartes-mcp-*-jar-with-dependencies.jar 2>/dev/null | head -n1 || true)
fi

if [ -z "${MAIN_JAR:-}" ]; then
    echo "Unable to find Descartes shaded JAR in $PROJECT_ROOT/target." >&2
    exit 1
fi

exec java \
    --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
    -cp "$MAIN_JAR" \
    com.bitsapplied.descartes.debugger.MCPRemoteDebugProxy \
    "${ARGS[@]}"
