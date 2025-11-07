#!/bin/bash
# Robust script to run the Profiler Workflow Example
#
# Usage:
#   ./run-profiler-demo.sh              # Automated demo mode
#   ./run-profiler-demo.sh --interactive # Interactive mode (waits for MCP client)

set -e

echo "=== Descartes Profiler Workflow Example ==="
echo

# Check if JAR exists, build if needed
JAR=$(ls -t target/descartes-mcp-*-jar-with-dependencies.jar 2>/dev/null | head -n1)

if [ -z "$JAR" ]; then
    echo "JAR file not found. Building..."
    mvn clean package -DskipTests -q
    echo "Build complete."
    echo
    
    # Find the JAR again after build
    JAR=$(ls -t target/descartes-mcp-*-jar-with-dependencies.jar 2>/dev/null | head -n1)
    
    if [ -z "$JAR" ]; then
        echo "Error: Build failed - JAR file still not found."
        exit 1
    fi
fi

echo "Using JAR: $JAR"
echo

# Detect a free port for MCP server (default 9080)
MCP_PORT=9080
for port in 9080 9081 9082 9083 9084; do
    if ! lsof -i :$port >/dev/null 2>&1; then
        MCP_PORT=$port
        break
    fi
done

echo "Using MCP port: $MCP_PORT"
echo

# Run the profiler demo with JDK 11+ flags (required for JFR)
exec java \
    -Ddescartes.mcp.port=$MCP_PORT \
    -cp "$JAR" \
    com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample \
    "$@"
