#!/bin/bash
# Robust script to run the Descartes MCP Remote Debug Proxy
#
# Usage:
#   ./run-remote-proxy.sh                              # Use defaults (localhost:5005)
#   ./run-remote-proxy.sh --jdwp-port 5005             # Local debugging
#   ./run-remote-proxy.sh --jdwp-host staging.example.com --jdwp-port 5005
#   ./run-remote-proxy.sh --config proxy-config.json   # Use config file
#   ./run-remote-proxy.sh --help                       # Show help

set -e

echo "=== Descartes MCP Remote Debug Proxy ==="
echo

# Check if help requested
if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    exec java -cp "$(ls -t target/descartes-mcp-*-jar-with-dependencies.jar 2>/dev/null | head -n1)" \
        com.bitsapplied.descartes.debugger.MCPRemoteDebugProxy --help
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

# Check if MCP port is available (default 9090)
MCP_PORT=9090
if lsof -i :$MCP_PORT >/dev/null 2>&1; then
    echo "Warning: Port $MCP_PORT is already in use"
    echo "You can specify a different port with --mcp-port <port>"
    echo
fi

# Parse config to show what we're connecting to
JDWP_HOST="localhost"
JDWP_PORT="5005"

# Simple argument parsing to extract host/port for display
for ((i=1; i<=$#; i++)); do
    arg="${!i}"
    if [ "$arg" = "--jdwp-host" ]; then
        j=$((i+1))
        JDWP_HOST="${!j}"
    elif [ "$arg" = "--jdwp-port" ]; then
        j=$((i+1))
        JDWP_PORT="${!j}"
    elif [ "$arg" = "--mcp-port" ]; then
        j=$((i+1))
        MCP_PORT="${!j}"
    fi
done

echo "Configuration:"
echo "  MCP Server Port:  $MCP_PORT (for MCP client connections)"
echo "  JDWP Target:      $JDWP_HOST:$JDWP_PORT (target JVM to debug)"
echo
echo "The proxy will expose debugging capabilities through MCP protocol."
echo "Use an MCP client (like Claude Desktop) to connect on port $MCP_PORT."
echo
echo "Starting proxy..."
echo

# Run with proper JVM flags for JDK 17+
exec java \
    --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
    -jar "$MAIN_JAR" \
    "$@"
