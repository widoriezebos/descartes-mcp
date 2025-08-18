#!/bin/bash
# Run the Descartes MCP server with hot reload support

# Find the main JAR with dependencies
MAIN_JAR=$(find target -name "*-jar-with-dependencies.jar" -type f | head -1)

if [ -z "$MAIN_JAR" ]; then
    echo "Error: Main JAR not found. Please run 'mvn package' first."
    exit 1
fi

echo "Starting Descartes MCP Server with Hot Reload Support"
echo "======================================================"
echo "JAR: $MAIN_JAR"
echo ""
echo "The same JAR serves as both the application and the Java agent."
echo "Hot reload will be available for classes in the running JVM."
echo "Use the 'hot_reload_classes' tool via MCP client to reload classes."
echo ""
echo "Starting server on port 9080..."
echo ""

# Run with the same JAR as both agent and application
java -javaagent:$MAIN_JAR \
     -jar $MAIN_JAR "$@"