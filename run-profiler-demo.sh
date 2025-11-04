#!/bin/bash
# Run the Descartes Profiler Workflow Example
#
# Usage:
#   ./run-profiler-demo.sh              # Automated demo mode
#   ./run-profiler-demo.sh --interactive # Interactive mode (server stays running)

echo "Starting Descartes Profiler Workflow Example"
echo "============================================="
echo ""
echo "This demo showcases JFR-based performance profiling:"
echo "  - CPU profiling (find computation bottlenecks)"
echo "  - Allocation profiling (memory leak investigation)"
echo "  - Comprehensive profiling (CPU, memory, locks, I/O, GC)"
echo "  - Interactive flame graph generation"
echo ""
echo "Includes realistic workloads:"
echo "  - Computation (Fibonacci, primes, matrix operations)"
echo "  - Allocation (String concatenation, collections)"
echo "  - Concurrency (lock contention)"
echo "  - I/O (buffered vs unbuffered)"
echo ""
echo "Requirements: JDK 11+ for JFR support"
echo "Output saved to: ./profiler-demo-output/"
echo ""
echo "Server will start on port 9080"
echo ""

if [ "$1" = "--interactive" ] || [ "$1" = "-i" ]; then
    echo "Mode: INTERACTIVE (server stays running)"
    echo "Press Enter to stop when ready..."
    echo ""
    mvn exec:java -Dexec.mainClass="com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample" \
                  -Dexec.args="--interactive"
else
    echo "Mode: AUTOMATED DEMO (runs all profiling scenarios then exits)"
    echo "Use --interactive flag to keep server running"
    echo ""
    mvn exec:java -Dexec.mainClass="com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample"
fi
