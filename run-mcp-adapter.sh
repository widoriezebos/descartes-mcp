#!/bin/bash
#
# Launch script for the Java-based MCP TCP adapter
#
# This adapter bridges stdin/stdout JSON-RPC (used by MCP clients like Claude Code)
# with TCP connections to the Descartes MCP server. It's a drop-in replacement for
# the Node.js adapter (mcp-tcp-adapter.js) with no Node.js dependency.
#
# Usage:
#   ./run-mcp-adapter.sh
#
# Environment Variables (same as Node.js adapter):
#   MCP_HOST                  - MCP server hostname (default: localhost)
#   MCP_PORT                  - MCP server TCP port (default: 9080)
#   MCP_DEBUG                 - Enable debug logging (default: false)
#   MCP_RECONNECT_MIN_DELAY   - Min reconnection delay in ms (default: 500)
#   MCP_RECONNECT_MAX_DELAY   - Max reconnection delay in ms (default: 5000)
#   MCP_MESSAGE_QUEUE_SIZE    - Max queued messages (default: 100)
#   MCP_REQUEST_TIMEOUT       - Request timeout in ms (default: 30000)
#
# Requirements:
#   - JDK 16+ runtime
#   - Maven (for auto-build)
#

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_NAME="descartes-mcp-0.0.1-SNAPSHOT-jar-with-dependencies.jar"
JAR_PATH="$PROJECT_DIR/target/$JAR_NAME"

# Auto-build if JAR doesn't exist or is older than pom.xml
if [ ! -f "$JAR_PATH" ] || [ "$PROJECT_DIR/pom.xml" -nt "$JAR_PATH" ]; then
    echo "[MCP-Adapter] JAR not found or out of date. Building..." >&2
    cd "$PROJECT_DIR"
    mvn clean package -DskipTests -q >&2
    echo "[MCP-Adapter] Build complete: $JAR_PATH" >&2
fi

# Launch the adapter
exec java -cp "$JAR_PATH" com.bitsapplied.descartes.mcp.adapter.McpTcpAdapter
