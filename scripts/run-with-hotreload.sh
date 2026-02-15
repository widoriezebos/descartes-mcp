#!/bin/bash
# Robust script to run the Descartes MCP server with hot reload support
#
# Usage:
#   ./scripts/run-with-hotreload.sh              # Interactive mode (waits for Enter)
#   ./scripts/run-with-hotreload.sh --continuous # Continuous mode (until killed)

set -e

echo "=== Descartes MCP Server with Hot Reload Support ==="
echo

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

# Detect a free port for MCP server (default 9080)
MCP_PORT=9080
for port in 9080 9081 9082 9083 9084; do
    if ! lsof -i :$port >/dev/null 2>&1; then
        MCP_PORT=$port
        break
    fi
done

echo "The same JAR serves as both the application and the Java agent."
echo "Hot reload will be available for classes in the running JVM."
echo "Use the 'hot_reload_classes' tool via MCP client to reload classes."
echo
echo "Starting server on port $MCP_PORT..."
echo

# Run with the same JAR as both agent and application
# Include JDK 17+ flags for proper agent/JDI access
exec java \
    -javaagent:"$MAIN_JAR" \
    -XX:+EnableDynamicAgentLoading \
    --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
    -Ddescartes.mcp.port=$MCP_PORT \
    -jar "$MAIN_JAR" \
    "$@"