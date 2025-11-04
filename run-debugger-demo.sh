#!/bin/bash
# Run the Descartes Debugger Workflow Example
#
# Usage:
#   ./run-debugger-demo.sh              # Automated demo mode
#   ./run-debugger-demo.sh --interactive # Interactive mode (server stays running)

echo "Starting Descartes Debugger Workflow Example"
echo "============================================="
echo ""
echo "This demo showcases all 8 debugger tools through realistic scenarios:"
echo "  - Basic debugging (stepping, variables, expressions)"
echo "  - Bug hunting (6 intentional bugs to find)"
echo "  - Data structures (nested objects, collections)"
echo "  - Concurrency (threads, deadlocks, race conditions)"
echo "  - Exceptions (NPE, chaining, custom exceptions)"
echo "  - Call stacks (recursion, deep chains)"
echo ""
echo "Server will start on port 9080"
echo "Connect your MCP client to interact with the debugger"
echo ""

if [ "$1" = "--interactive" ] || [ "$1" = "-i" ]; then
    echo "Mode: INTERACTIVE (server stays running)"
    echo "Press Enter to stop when ready..."
    echo ""
    mvn exec:java -Dexec.mainClass="com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample" \
                  -Dexec.args="--interactive"
else
    echo "Mode: AUTOMATED DEMO (runs all scenarios then exits)"
    echo "Use --interactive flag to keep server running"
    echo ""
    mvn exec:java -Dexec.mainClass="com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample"
fi
