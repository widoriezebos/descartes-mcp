#!/bin/bash
# Robust script to run the Debugger Workflow Example
#
# Usage:
#   ./run-debugger-demo.sh              # Automated demo mode
#   ./run-debugger-demo.sh --interactive # Interactive mode (waits for MCP client)

set -e

echo "=== Descartes Debugger Workflow Example ==="
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

# Detect a free port for JDWP (default 5005, try alternatives if occupied)
JDWP_PORT=5005
for port in 5005 5006 5007 5008 5009; do
    if ! lsof -i :$port >/dev/null 2>&1; then
        JDWP_PORT=$port
        break
    fi
done

echo "Using JDWP port: $JDWP_PORT"
echo "The example will debug its own running JVM on this port."
echo

# Run with all necessary JVM flags for JDK 17+ including JDWP for self-debugging
# The DebuggerWorkflowExample will attach to this JVM's JDWP port
exec java \
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$JDWP_PORT \
    -XX:+EnableDynamicAgentLoading \
    -Xshare:off \
    --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
    --add-opens jdk.jdi/com.sun.jdi=ALL-UNNAMED \
    --add-opens jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED \
    -cp "$JAR" \
    com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample \
    "$@"
