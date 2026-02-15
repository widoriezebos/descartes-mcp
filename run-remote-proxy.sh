#!/bin/bash
# Robust script to run the Descartes MCP Remote Debug Proxy
#
# Usage:
#   ./run-remote-proxy.sh                              # Use defaults (localhost:5005)
#   ./run-remote-proxy.sh --jdwp-port 5005             # Local debugging
#   ./run-remote-proxy.sh --jdwp-host staging.example.com --jdwp-port 5005
#   ./run-remote-proxy.sh --auto-discover              # Auto-discover single JDWP process
#   ./run-remote-proxy.sh --auto-discover --process-pattern "myapp"      # Pattern-based discovery
#   ./run-remote-proxy.sh --log-file logs/descartes-proxy.log            # Mirror output to log file
#   ./run-remote-proxy.sh --config proxy-config.json   # Use config file
#   ./run-remote-proxy.sh --help                       # Show help

set -euo pipefail

echo "=== Descartes MCP Remote Debug Proxy ==="
echo

# Parse config to show what we're connecting to
JDWP_HOST="localhost"
JDWP_PORT="5005"
AUTO_DISCOVER="false"
PROCESS_PATTERN=""
MCP_PORT=9090
LOG_FILE=""
SHOW_HELP="false"
PROXY_ARGS=()

i=1
while [ $i -le $# ]; do
    arg="${!i}"
    case "$arg" in
        --jdwp-host)
            i=$((i + 1))
            if [ $i -gt $# ]; then
                echo "Error: --jdwp-host requires a value."
                exit 1
            fi
            JDWP_HOST="${!i}"
            PROXY_ARGS+=("--jdwp-host" "$JDWP_HOST")
            ;;
        --jdwp-port)
            i=$((i + 1))
            if [ $i -gt $# ]; then
                echo "Error: --jdwp-port requires a value."
                exit 1
            fi
            JDWP_PORT="${!i}"
            PROXY_ARGS+=("--jdwp-port" "$JDWP_PORT")
            ;;
        --mcp-port)
            i=$((i + 1))
            if [ $i -gt $# ]; then
                echo "Error: --mcp-port requires a value."
                exit 1
            fi
            MCP_PORT="${!i}"
            PROXY_ARGS+=("--mcp-port" "$MCP_PORT")
            ;;
        --process-pattern)
            i=$((i + 1))
            if [ $i -gt $# ]; then
                echo "Error: --process-pattern requires a value."
                exit 1
            fi
            PROCESS_PATTERN="${!i}"
            PROXY_ARGS+=("--process-pattern" "$PROCESS_PATTERN")
            ;;
        --log-file)
            i=$((i + 1))
            if [ $i -gt $# ]; then
                echo "Error: --log-file requires a value."
                exit 1
            fi
            LOG_FILE="${!i}"
            ;;
        --auto-discover)
            AUTO_DISCOVER="true"
            PROXY_ARGS+=("--auto-discover")
            ;;
        --help|-h)
            SHOW_HELP="true"
            PROXY_ARGS+=("--help")
            ;;
        *)
            PROXY_ARGS+=("$arg")
            ;;
    esac
    i=$((i + 1))
done

if [ "$SHOW_HELP" = "true" ]; then
    echo "Script options:"
    echo "  --log-file <path>   Also write proxy stdout/stderr to this file (with console output)"
    echo
fi

# Check if JAR exists, build if needed
MAIN_JAR=$(ls -t target/descartes-mcp-*-jar-with-dependencies.jar 2>/dev/null | head -n1)

if [ -z "$MAIN_JAR" ]; then
    echo "JAR file not found. Building..."
    mvn clean package -DskipTests -q
    echo "Build complete."
    echo

    # Find the JAR again after build
    MAIN_JAR=$(ls -t target/descartes-mcp-*-jar-with-dependencies.jar 2>/dev/null | head -n1)

    if [ -z "$MAIN_JAR" ]; then
        echo "Error: Build failed - JAR file still not found."
        exit 1
    fi
fi

echo "Using JAR: $MAIN_JAR"
echo

# For help, delegate directly to proxy class and exit.
if [ "$SHOW_HELP" = "true" ]; then
    exec java \
        --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
        -cp "$MAIN_JAR" \
        com.bitsapplied.descartes.debugger.MCPRemoteDebugProxy \
        --help
fi

# Check if selected MCP port is available
if lsof -i :"$MCP_PORT" >/dev/null 2>&1; then
    echo "Warning: Port $MCP_PORT is already in use"
    echo "You can specify a different port with --mcp-port <port>"
    echo
fi

echo "Configuration:"
echo "  MCP Server Port:  $MCP_PORT (for MCP client connections)"

if [ "$AUTO_DISCOVER" = "true" ]; then
    if [ -n "$PROCESS_PATTERN" ]; then
        echo "  Auto-Discovery:   Enabled (pattern: '$PROCESS_PATTERN')"
    else
        echo "  Auto-Discovery:   Enabled (will auto-select if single process found)"
    fi
else
    echo "  JDWP Target:      $JDWP_HOST:$JDWP_PORT (target JVM to debug)"
fi

echo
echo "The proxy will expose debugging capabilities through MCP protocol."
echo "Use an MCP client (like Claude Code) to connect on port $MCP_PORT."
if [ -n "$LOG_FILE" ]; then
    echo "Proxy logs:          $LOG_FILE"
fi
echo
echo "Starting proxy..."
echo

# Run with proper JVM flags for JDK 17+
if [ -n "$LOG_FILE" ]; then
    mkdir -p "$(dirname "$LOG_FILE")"
    java \
        --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
        -cp "$MAIN_JAR" \
        com.bitsapplied.descartes.debugger.MCPRemoteDebugProxy \
        "${PROXY_ARGS[@]}" 2>&1 | tee -a "$LOG_FILE"
else
    exec java \
        --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
        -cp "$MAIN_JAR" \
        com.bitsapplied.descartes.debugger.MCPRemoteDebugProxy \
        "${PROXY_ARGS[@]}"
fi
