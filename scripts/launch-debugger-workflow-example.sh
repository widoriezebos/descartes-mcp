#!/usr/bin/env bash
#
# Build (if needed) and launch DebuggerWorkflowExample with JDWP
# for interactive debugging via the Descartes MCP debugger tools.
#
# Usage:
#   scripts/launch-debugger-workflow-example.sh [--port PORT] [--name NAME]
#
# The process listens for JDWP connections on localhost:PORT (default 5005)
# and starts the MCP server on port 9080.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

NAME="buggy-calc"
JDWP_PORT=5005
SUSPEND="y"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port)       JDWP_PORT="$2"; shift 2 ;;
    --name)       NAME="$2"; shift 2 ;;
    --no-suspend) SUSPEND="n"; shift ;;
    -h|--help)
      echo "Usage: $0 [--port JDWP_PORT] [--name NAME] [--no-suspend]"
      echo "  --port        JDWP listen port (default: 5005)"
      echo "  --name        Logical process name (default: buggy-calc)"
      echo "  --no-suspend  Start the JVM without waiting for debugger attach"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

cd "$PROJECT_DIR"

# ---------------------------------------------------------------------------
# Build the shaded JAR if missing or stale
# ---------------------------------------------------------------------------
shopt -s nullglob
jars=(target/descartes-mcp-*-jar-with-dependencies.jar)
shopt -u nullglob

JAR="${jars[0]:-}"

if [[ -z "$JAR" ]] || \
   [[ -n "$(find src -newer "$JAR" -name '*.java' 2>/dev/null | head -1)" ]]; then
  echo "Building shaded JAR (mvn package -DskipTests) ..."
  mvn -q package -DskipTests
  shopt -s nullglob
  jars=(target/descartes-mcp-*-jar-with-dependencies.jar)
  shopt -u nullglob
  JAR="${jars[0]:-}"
fi

if [[ -z "$JAR" ]]; then
  echo "ERROR: shaded JAR not found after build." >&2
  exit 1
fi

echo "JAR:       $JAR"
echo "JDWP port: $JDWP_PORT"
echo "Suspend:   $SUSPEND"
echo "MCP port:  9080"
echo "Name:      $NAME"
echo ""

if [[ "$SUSPEND" == "y" ]]; then
  echo "JVM will suspend on startup — attach a debugger to localhost:${JDWP_PORT} to resume."
  echo ""
fi

# ---------------------------------------------------------------------------
# Launch via launch-managed-nontty.sh
# ---------------------------------------------------------------------------
# The managed launcher requires all fds to be non-TTY:
#   </dev/null   -> stdin non-TTY
#   | tee ...    -> stdout non-TTY (pipe)
#   2>&1         -> stderr follows stdout into the pipe
# ---------------------------------------------------------------------------
LOG_DIR=".pids"
mkdir -p "$LOG_DIR"

"$SCRIPT_DIR/launch-managed-nontty.sh" \
  --name "$NAME" \
  -- java \
     -javaagent:"$JAR" \
     -XX:+EnableDynamicAgentLoading \
     -agentlib:jdwp=transport=dt_socket,server=y,suspend=${SUSPEND},address="localhost:${JDWP_PORT}" \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
     -cp "$JAR" \
     com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample \
     --interactive \
  </dev/null 2>&1 | tee "${LOG_DIR}/${NAME}.log"
