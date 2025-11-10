#!/bin/bash
# Launches the Descartes remote debug proxy together with the MCP TCP adapter.
#
# Usage:
#   ./run-proxy-adapter.sh                              # Defaults (JDWP localhost:5005, MCP 9090)
#   ./run-proxy-adapter.sh --jdwp-port 5005             # Override JDWP port
#   ./run-proxy-adapter.sh --jdwp-host staging.example.com --jdwp-port 5005
#   ./run-proxy-adapter.sh --config proxy-config.json   # Use configuration file
#   ./run-proxy-adapter.sh --help                       # Show help / usage

set -e

echo "=== Descartes MCP Remote Debug Proxy + TCP Adapter ==="
echo

# Print usage via JVM if requested
if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    exec java -cp "$(ls -t target/descartes-mcp-*-jar-with-dependencies.jar 2>/dev/null | head -n1)" \
        com.bitsapplied.descartes.mcp.adapter.McpProxyAdapter --help
fi

# Ensure shaded JAR is available
MAIN_JAR=$(ls -t target/descartes-mcp-*-jar-with-dependencies.jar 2>/dev/null | head -n1)

if [ -z "$MAIN_JAR" ]; then
    echo "JAR file not found. Building..."
    mvn clean package -DskipTests -q
    echo "Build complete."
    echo
    MAIN_JAR=$(ls -t target/descartes-mcp-*-jar-with-dependencies.jar 2>/dev/null | head -n1)
    if [ -z "$MAIN_JAR" ]; then
        echo "Error: Build failed - JAR file still not found."
        exit 1
    fi
fi

echo "Using JAR: $MAIN_JAR"
echo

# Extract relevant CLI args for display
MCP_PORT=9090
JDWP_HOST="localhost"
JDWP_PORT="5005"

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

# Warn if MCP port already in use
if lsof -i :$MCP_PORT >/dev/null 2>&1; then
    echo "Warning: Port $MCP_PORT is already in use."
    echo "Specify a different MCP port with --mcp-port <port> if needed."
    echo
fi

echo "Configuration:"
echo "  MCP Server Port:  $MCP_PORT (adapter connects here)"
echo "  JDWP Target:      $JDWP_HOST:$JDWP_PORT"
echo
echo "Starting combined proxy + adapter..."
echo

exec java \
    --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
    -cp "$MAIN_JAR" \
    com.bitsapplied.descartes.mcp.adapter.McpProxyAdapter \
    "$@"
