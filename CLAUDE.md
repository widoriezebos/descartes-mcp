# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**IMPORTANT - What Belongs in This File:**
This file should ONLY contain information that agents NEED to know for proper functioning:
- ✅ Build commands, test commands, project structure
- ✅ Architecture patterns that affect how code should be written
- ✅ Critical constraints or requirements (e.g., minimum Java version, thread safety requirements)
- ✅ Integration instructions for using the project
- ❌ Release notes, changelogs, or "what changed" information
- ❌ Implementation details of specific fixes or features
- ❌ Historical context about why decisions were made (belongs in code comments/Javadoc)

When updating this file, ask: "Does an agent need this information to write correct code?" If not, it belongs elsewhere (Javadoc, README, CHANGELOG, code comments).

## Project Overview

Descartes MCP is a Java-based Model Context Protocol (MCP) server that provides deep introspection, monitoring, debugging, and REPL capabilities for Java applications. It enables AI assistants to interact with running Java processes through tools and resources.

## Build and Development Commands

```bash
# Build the project
mvn clean compile

# Run tests (excludes concurrency tests and hot reload tests by default)
mvn test

# Run concurrency tests only
mvn test -Pconcurrency-tests

# Run hot reload tests only (requires agent)
mvn test -Phot-reload-tests

# Run all tests including concurrency tests
mvn test -Pall-tests

# Package the application with dependencies
mvn clean package

# Run the example server (standard mode - no hot reload)
mvn exec:java

# Run with hot reload support - EASIEST WAY (uses Maven profile)
# This automatically builds the agent JAR and starts with hot reload enabled
mvn compile exec:exec -Prun-with-agent

# Or manually with hot reload support
java -javaagent:target/descartes-mcp-*-jar-with-dependencies.jar \
     -jar target/descartes-mcp-*-jar-with-dependencies.jar

# Or use the convenient script for hot reload
./run-with-hotreload.sh

# Build Eclipse-specific output (when using Eclipse IDE)
mvn clean compile -Peclipse-m2e
```

## Test Environment Management

**CRITICAL: Always Clean Leftover Test Processes Before Running Tests**

When Maven test runs are interrupted (Ctrl+C, IDE stop, timeout, etc.), Surefire forked JVM processes may remain running in the background. These "zombie" processes will cause subsequent test runs to fail with:
- Port conflicts (e.g., "Address already in use: 9080")
- File lock conflicts
- Cryptic test failures
- Hanging test executions

### Detection

Check for leftover surefire processes:
```bash
# Check if any surefire processes are running
ps aux | grep surefirebooter | grep -v grep

# Or use lsof to check if test ports are occupied
lsof -i :9080
```

### Cleanup

**Always run this before starting new tests:**
```bash
# Kill all leftover surefire processes (macOS/Linux)
pkill -9 -f surefirebooter
```

### Recommended Workflow: Combined Commands

Use these one-liners to automatically clean before running tests:

```bash
# Default tests (excludes concurrency and hot reload)
pkill -9 -f surefirebooter 2>/dev/null; mvn test

# Concurrency tests
pkill -9 -f surefirebooter 2>/dev/null; mvn test -Pconcurrency-tests

# Hot reload tests (requires agent)
pkill -9 -f surefirebooter 2>/dev/null; mvn test -Phot-reload-tests

# All tests
pkill -9 -f surefirebooter 2>/dev/null; mvn test -Pall-tests

# Clean, compile, and test
pkill -9 -f surefirebooter 2>/dev/null; mvn clean test
```

**Note:** The `2>/dev/null` suppresses error messages if no processes are found, making the command safe to run even when no cleanup is needed.

### For Claude Code Agents

**MANDATORY PRE-FLIGHT CHECK**: Before executing any `mvn test` command, you MUST:
1. Check for leftover surefire processes
2. Kill them if found using `pkill -9 -f surefirebooter`
3. Then proceed with the test command

This is NON-NEGOTIABLE. Test failures due to leftover processes waste time and create confusing error messages.

## Architecture

### Core Components

**MCPServer** (`com.bitsapplied.descartes.MCPServer`): Main server implementation that handles JSON-RPC protocol, manages client connections on a configurable port (default 9080), and routes requests to registered tools and resources.

**Tools** (`com.bitsapplied.descartes.tools.*`): Implement the `MCPTool` interface to provide callable functions:
- `JShellTool`: Interactive Java REPL with session management and timeout protection
  - **Timeout Mechanism**: Uses JShell's built-in `stop()` method combined with thread interruption to prevent infinite loops
  - **Default Timeout**: 30 seconds (configurable via `timeout_seconds` parameter)
  - **How It Works**: Schedules a `JShell.stop()` call before timeout expires; cancels if evaluation completes successfully
  - **Known Limitations** (inherent to JShell API):
    - May not stop code blocked on I/O operations (e.g., `System.in.read()`)
    - Cannot stop code that catches `ThreadDeath` exceptions and ignores them
    - Native code that doesn't check interrupt flags may continue running
    - Code creating non-daemon threads will leave those threads running
  - **Best Practice**: Design REPL code to be responsive and avoid blocking operations
  - **Implementation**: See `JShellTool.java:78-193`, `JShellService.java:184-191`
- `JShellSessionTool`: Manages JShell sessions lifecycle
- `ObjectInspectorTool`: Deep object inspection without code execution
- `HotClassReloadTool`: Hot reload Java classes at runtime (requires agent mode)
- `ProcessInspectorTool`: Process and thread information
- `SystemMonitoringTool`: System metrics and monitoring
- `ThreadAnalyzerTool`: Thread state and deadlock detection
  - **Architecture**: Modular design using Strategy and Chain of Responsibility patterns
  - **Operations** (`com.bitsapplied.descartes.tools.threadanalyzer.operations.*`): Strategy pattern for different analysis types
    - `ThreadListOperation`: Lightweight thread summaries without stack traces
    - `ThreadInspectOperation`: Detailed view of specific threads by ID or name
    - `ThreadSearchOperation`: Find threads by criteria with optional details
    - `DeadlockDetectionOperation`: Detect circular dependencies between threads
    - `ThreadDumpOperation`: Full text-based thread dumps for offline analysis
    - `AbstractThreadOperation`: Base class with shared utilities (458 lines) including ReDoS-safe regex compilation, stack trace formatting, thread ID extraction, and deadlock chain analysis
  - **Filters** (`com.bitsapplied.descartes.tools.threadanalyzer.filters.*`): Chain of Responsibility pattern for thread filtering
    - `StateFilter`: Filter by thread state (RUNNABLE, BLOCKED, WAITING, etc.)
    - `NamePatternFilter`: Filter by regex pattern or substring matching
    - `CpuTimeFilter`: Filter by minimum CPU time (when supported)
    - `DaemonFilter`: Filter by daemon status
    - `FilterChain`: Chains multiple filters together, applies only applicable filters
  - **Builders** (`com.bitsapplied.descartes.tools.threadanalyzer.builders.*`): Fluent API for constructing thread information
    - `ThreadInfoBuilder`: Fluent builder with methods like `withCpuTime()`, `withLocks()`, `withStackTrace()`, `withMonitors()`, `withSynchronizers()` to eliminate code duplication
  - **Design Goals**: Achieved 79% line reduction (1,053→260 lines) while maintaining 100% backward compatibility (all 36 tests pass without modification)
- `MemoryAnalyzerTool`: Memory usage analysis
- `ExceptionAnalysisTool`: Exception and error analysis
- `LoggingIntegrationTool`: Log4j2 integration for log capture
- **Debugger Tools** (`com.bitsapplied.descartes.debugger.*`): Full-featured debugger using Java Debug Interface (JDI)
  - `DebuggerSessionTool`: Attach/detach debugger, manage debug sessions
  - `DebuggerBreakpointsTool`: Set/remove breakpoints with conditions
  - `DebuggerThreadsTool`: List, suspend, and resume threads
  - `DebuggerStepTool`: Step over/into/out of methods
  - `DebuggerVariablesTool`: Inspect variables and object fields
  - `DebuggerStackTraceTool`: Capture and navigate stack frames
  - `DebuggerWatchTool`: Add watch expressions for monitoring
  - `DebuggerEvaluateTool`: Evaluate expressions in debugged context
- **Profiler Tools** (`com.bitsapplied.descartes.profiler.tools.*`): JFR-based performance profiling
  - `ProfilerStartTool`: Start profiling sessions (CPU, allocation, locks, I/O, GC)
  - `ProfilerStopTool`: Force-stop active profiling sessions
  - `ProfilerHotspotsTool`: Get ranked performance hotspots with source locations
  - `ProfilerCallTreeTool`: Analyze method call trees and execution hierarchies
  - `ProfilerListTool`: List stored profiles and active recording sessions
  - `ProfilerExportTool`: Export profiles as JSON/text/interactive HTML flame graphs

**Resources** (`com.bitsapplied.descartes.resources.*`): Implement the `MCPResource` interface to expose read-only data:
- `ClasspathResource`: Classpath information
- `SystemPropertiesResource`: JVM system properties
- `MetricsResource`: Application metrics
- `ThreadDumpResource`: Thread dump information
- `MBeanResource`: JMX MBean access
- `ApplicationContextResource`: Access to application context objects

**Hot Reload Subsystem** (`com.bitsapplied.descartes.hotreload.*`): Provides runtime class redefinition capabilities:
- `HotReloadAgent`: Java agent that instruments the JVM for class tracking and redefinition
- `HotReloadService`: Core service that orchestrates the reload process
- `ClassLoadTracker`: Monitors class loading and tracks source locations
- `ClassStructureAnalyzer`: Uses ASM to analyze bytecode and validate compatibility
- Requires running with `-javaagent:descartes-mcp-jar-with-dependencies.jar`

**Performance Profiling Subsystem** (`com.bitsapplied.descartes.profiler.*`): JFR-based low-overhead performance profiling for production-safe analysis. Captures CPU samples, memory allocations, lock contention, I/O events, and garbage collection with configurable overhead (0.5%-2%):

- **ProfilerService**: Main service managing JFR recordings, profile storage, and lifecycle. Handles automatic session stopping, parsing, and profile retention (default: 100 profiles max).
- **ProfilerSettings**: Configuration builder for enabling/disabling profiling, storage paths, sampling rates, event types, and package filtering.
- **JFRRecorder**: JFR recording implementation using `jdk.jfr.Recording` API. Configures event types and thresholds based on ProfilerConfig. Requires JDK 11+ for JFR support.
- **JFRParser**: Parses JFR binary files into ProfileSnapshot objects. Extracts CPU samples, allocation events, lock durations, and builds call trees with per-method statistics.
- **CallTreeBuilder**: Constructs method call hierarchies from stack traces, computing self-time and cumulative time for each node.
- **ProfileStore**: Persistent storage managing .jfr files and parsed snapshots with LRU eviction when capacity is reached.
- **FlameGraphExporter**: Generates interactive HTML flame graphs with embedded SVG and JavaScript. Supports zoom, search, tooltips, and color-coded visualization by package.
- **Profile Types**:
  - `cpu` - CPU sampling only (10ms interval, ~1% overhead) - Default, best for finding computation bottlenecks
  - `allocation` - Memory allocation tracking (for memory leak investigation)
  - `comprehensive` - All events: CPU, allocation, locks, I/O, GC (~2% overhead) - Deep investigation
  - `lightweight` - CPU sampling only (20ms interval, ~0.5% overhead) - Production monitoring
- **Requirements**: JDK 11+ for JFR API, storage space for .jfr files
- **Profile IDs**: Timestamped format `dd-MM-yyyy_HH.mm.ss-profile-<uuid>` for easy identification

**Typical Profiling Workflow:**
1. Start profiling: `profiler_start` with duration (10-300s) and profile type
2. Wait for auto-stop or manually stop: `profiler_stop`
3. Analyze hotspots: `profiler_hotspots` to find CPU/memory bottlenecks (top methods by %)
4. Drill down: `profiler_call_tree` to see what hotspot methods are calling
5. Visualize: `profiler_export` with format `flamegraph` to generate interactive HTML
6. Open in browser: The HTML includes zoom, search, and tooltips for visual exploration

**Interactive Flame Graphs:**
The flame graph visualization provides intuitive performance analysis:
- **Width**: Time/samples spent in method (wider = more expensive)
- **Height**: Call stack depth (bottom = entry points, top = leaf methods)
- **Colors**: Hash-based coloring by package/class for visual grouping
- **Interactivity**: Click to zoom, search to highlight, hover for tooltips with percentages
- **Self-contained**: Single HTML file with embedded SVG and JavaScript
- **Similar to**: Datadog/Honeycomb flame graphs but generated locally without external dependencies

**Context Map**: Central mechanism for sharing application objects between tools/resources without tight coupling. Tools can access application services, repositories, and other components through this context.

### Key Design Patterns

- **Generic Context Pattern**: Tools and resources access application objects through a `Map<String, Object>` context, avoiding direct dependencies
- **Session Management**: JShell sessions have configurable timeouts and isolation between different AI conversation contexts
- **Resource Registry**: URI-based resource access pattern (e.g., `app://classpath`, `app://metrics`)
- **Strategy Pattern**: Used extensively in ThreadAnalyzerTool where different operations (thread_list, thread_inspect, thread_search, deadlocks, thread_dump) are implemented as separate strategy classes, all implementing the `ThreadOperation` interface. This makes it trivial to add new analysis operations without modifying existing code.
- **Chain of Responsibility Pattern**: Implemented in ThreadAnalyzerTool's filtering system where multiple filters (`StateFilter`, `NamePatternFilter`, `CpuTimeFilter`, `DaemonFilter`) are chained together via `FilterChain`. Each filter processes the thread list and passes it to the next filter, with the ability to short-circuit if not applicable.
- **Builder Pattern**: ThreadInfoBuilder uses a fluent API (`withCpuTime()`, `withLocks()`, etc.) to construct thread information maps incrementally, eliminating code duplication and improving readability

## Maven Profiles

The project includes several Maven profiles for different use cases:

### Testing Profiles
- **Default**: `mvn test` - Excludes concurrency and hot reload tests for faster feedback
- **concurrency-tests**: `mvn test -Pconcurrency-tests` - Runs concurrency tests in isolation
- **hot-reload-tests**: `mvn test -Phot-reload-tests` - Runs hot reload tests with Java agent
- **all-tests**: `mvn test -Pall-tests` - Runs all tests including special categories

### Runtime Profiles
- **run-with-agent**: `mvn compile exec:exec -Prun-with-agent` - Runs SimpleMCPServerExample with hot reload agent
  - Automatically builds the agent JAR
  - Starts JVM with `-javaagent` flag
  - Enables continuous mode by default
  - Perfect for development with hot reload capability
  - **Important**: Keep `-XX:+EnableDynamicAgentLoading` and the `--add-opens` flags in any custom profile that launches with `-javaagent`; without them JPMS will block Attach/JDI access on JDK 17+.

### Build Profiles
- **eclipse-m2e**: `mvn clean compile -Peclipse-m2e` - Eclipse-specific build configuration

## Testing Approach

The project uses JUnit 5 with separate test profiles:
- Default tests exclude concurrency and hot reload tests for faster feedback
- Concurrency tests run in isolation to avoid interference
- Hot reload tests require the Java agent and run with `-Phot-reload-tests` profile
- Test suite `DescartesTestSuite` organizes all tests
- Hot reload tests use ASM for bytecode manipulation to test various reload scenarios

## Java Version

Minimum: Java 16 (uses records, text blocks, and stream.toList())
Configured: Java 23 in pom.xml for optimal performance

## Code Review Guidelines for AI Assistants

When reviewing code in this project, follow these rigorous guidelines to ensure accurate analysis:

### 1. Trace Complete Control Flow

**NEVER** claim a resource leak, race condition, or missing cleanup without tracing the COMPLETE execution path:

- **For lifecycle claims**: Read setup methods (@BeforeAll, @BeforeEach, constructors) AND teardown methods (@AfterAll, @AfterEach, dispose, close, shutdown)
- **For race conditions**: Trace the ENTIRE sequence including synchronization, happens-before relationships, and ordering guarantees documented in Javadoc
- **For resource leaks**: Follow resources from creation → usage → cleanup in ALL code paths (success, failure, exception)

**Example**: Before claiming "EventHub can fire events after reset()":
1. Read the resetSessionState() method (line 1121+)
2. Verify it stops EventHub BEFORE calling connectionManager.reset()
3. Check the Javadoc explaining this ordering
4. Only THEN assess if there's a race condition

### 2. Read Test Infrastructure Thoroughly

Before claiming missing test functionality:

- **Check base classes**: DebuggerTestBase, test utilities, shared fixtures
- **Check @BeforeAll and @AfterAll**: These often contain critical setup/teardown
- **Check helper methods**: verifyCleanState(), waitFor(), setupConnection() patterns
- **Check test profiles**: maven profiles may exclude certain test categories

**Example**: Before claiming "tests don't clean up state":
1. Check if test extends a base class with cleanup
2. Read @AfterEach methods in both test class AND base class
3. Look for verify/assert methods that enforce cleanliness
4. Check if cleanup is done in @AfterAll at the class level

### 3. Verify ALL Claims With Actual Code

**NEVER** make claims based on:
- Pattern matching (seeing Thread.sleep() doesn't mean it's wrong)
- Assumptions (assuming static state = global pollution)
- Incomplete reading (reading setup without reading teardown)
- Surface structure (seeing no cleanup in one method without checking callers)

**ALWAYS** verify by:
- Reading the specific lines mentioned
- Tracing through method calls
- Checking Javadoc and comments for design rationale
- Looking for compensating mechanisms (circuit breaker reset, port cache clearing)

### 4. Understand Context Before Criticizing

Code that appears problematic in isolation may be correct in context:

- **Thread.sleep()**: May be intentional and safe (e.g., in shutdown paths, test utilities, or with retry logic)
- **Static fields**: May be properly managed with reset methods in test lifecycle
- **No error handling**: May be intentional (fail-fast) or handled at a higher level
- **"Magic numbers"**: May be documented in comments or represent well-known standards

**Example**: Before criticizing Thread.sleep(100):
1. Check WHERE it's used (production hot path vs. test utility vs. shutdown)
2. Check if there's a comment explaining why
3. Check if there's retry/timeout logic around it
4. Consider if there's a better alternative in THIS specific context

### 5. Test Quality Assessment

When evaluating test quality, distinguish between:

- **API tests**: Verify tool interfaces, parameter validation, error handling
- **Integration tests**: Verify components work together correctly
- **Behavior tests**: Verify actual functionality (e.g., breakpoints suspend threads)
- **End-to-end tests**: Verify complete workflows from user perspective

**All are valid** - not every test needs to be end-to-end. However, be clear about what each test type provides and what gaps exist.

**Example**: Before claiming "tests are vanity tests":
1. Understand the test's PURPOSE (API contract vs. behavior verification)
2. Check if there ARE end-to-end tests elsewhere
3. Consider if API tests alone are sufficient for this component
4. Be specific about what's missing, not dismissive of what exists

### 6. Acknowledge When You're Wrong

If the user corrects you with specific evidence:

1. **Acknowledge the correction explicitly** - Don't deflect or equivocate
2. **Explain what you missed** - Show you understand WHY you were wrong
3. **Update your mental model** - Don't repeat the same error
4. **Reassess other findings** - If you were wrong about one thing, check others

### 7. Conservative Approach to Severity

When assigning severity to issues:

- **Critical**: Causes data corruption, security vulnerability, or guaranteed failure in production
- **High**: Causes intermittent failures, resource exhaustion, or significant performance degradation
- **Medium**: Code smell that could lead to bugs, or minor performance issue
- **Low**: Style issue, minor optimization opportunity, or documentation gap

**DO NOT** inflate severity to make your review seem more valuable. One accurate critical issue is worth more than ten false alarms.

### 8. Positive Recognition

Always acknowledge good practices you find:

- Comprehensive logging and metrics
- Well-documented design decisions
- Thorough error handling
- Proper resource management
- Good test coverage (even if not perfect)

This helps maintain credibility and shows balanced analysis.

### Summary

**The goal is accuracy and helpfulness, not finding issues.** A review that finds zero issues but provides deep understanding is more valuable than a review that lists 20 invalid problems.

## Integration Points

When integrating Descartes into an application:

1. Create a `Map<String, Object>` context with application objects
2. Instantiate `MCPServer` with settings and context
3. Register desired tools and resources
4. Start the server on a chosen port
5. Handle shutdown gracefully with shutdown hooks

### SimpleMCPServerExample

`com.bitsapplied.descartes.example.SimpleMCPServerExample` is a comprehensive example that showcases all available tools and resources. It demonstrates:
- Setting up the MCP server on port 9080
- Registering all built-in tools (JShell, monitoring, debugging, profiling)
- Registering all built-in resources (classpath, metrics, thread dumps, etc.)
- Adding sample objects to the context for JShell access
- Proper error handling for port conflicts
- **Profiler integration**: Configuring ProfilerService with storage path and enabling profiling tools
- **Smart mode detection**: Automatically detects environment and chooses appropriate mode
  - Interactive mode: When running in a terminal, waits for Enter key to stop
  - Continuous mode: When running in IDE/background, runs continuously until killed
- **Mode override options**:
  - Command line: `mvn exec:java -Dexec.args="--continuous"` or `-Dexec.args="-c"`
  - System property: `mvn exec:java -Ddescartes.continuous=true`
  - Eclipse IDE: Add `-Ddescartes.continuous=true` to VM arguments in Run Configuration

**Important Note - Log4j2 Configuration**: When running SimpleMCPServerExample outside of the test scope, you must configure the custom `InMemoryAppender` for the `LoggingIntegrationTool` to work. Either copy `/descartes-mcp/src/test/resources/log4j2.properties` to the main resources directory, or add these essential lines to your `log4j2.properties`:

```properties
# Register the custom appender package
packages = com.bitsapplied.descartes.util

# Configure the In-Memory Appender
appender.inMemory.type = InMemoryAppender
appender.inMemory.name = INMEMORY
appender.inMemory.layout.type = PatternLayout
appender.inMemory.layout.pattern = %d{dd-MM-yyyy HH:mm:ss} %5p %c{1}:%L - %m%n
appender.inMemory.maxBufferSize = 500
appender.inMemory.truncateBackTo = 400
appender.inMemory.loggerFilter = com.bitsapplied.

# Add to root logger
rootLogger.appenderRefs = console, inMemory
rootLogger.appenderRef.inMemory.ref = INMEMORY
```

### ProfilerWorkflowExample

`com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample` is a comprehensive example that demonstrates the complete profiler workflow with realistic workloads and flame graph generation. Located in `src/main/java/com/bitsapplied/descartes/example/profiler/`, this example showcases:

**What It Demonstrates:**
- Complete profiling workflow from start to flame graph export
- Different profile types (CPU, allocation, comprehensive, lightweight)
- Realistic workload generators (computation, memory allocation, concurrency, I/O)
- Hotspot analysis and call tree examination
- Interactive flame graph generation and interpretation
- Performance anti-patterns and their profiling signatures

**How to Run:**

```bash
# Automated Demo Mode - walks through all profiling scenarios
mvn compile exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample"

# Interactive Mode - keeps server running for manual MCP tool usage
mvn compile exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample" \
  -Dexec.args="--interactive"
```

**Included Components:**
- **ProfilerWorkflowExample.java** - Main orchestrator demonstrating complete profiling workflow
- **Workload Generators** (`workloads/` package):
  - `ComputationWorkload` - CPU-intensive operations (Fibonacci, primes, matrix multiplication)
  - `AllocationWorkload` - Memory allocation patterns (String concatenation, collections, serialization)
  - `ConcurrencyWorkload` - Lock contention scenarios (synchronized methods, concurrent maps)
  - `IOWorkload` - I/O operations (buffered/unbuffered, NIO, compression)
- **README.md** - Comprehensive documentation with usage guide and interpretation help

**Output Location:** All profiles and flame graphs are saved to `./profiler-demo-output/`

**Educational Value:**
- Shows realistic performance bottlenecks and how to identify them
- Demonstrates intentional anti-patterns for learning (String concatenation in loops, unbuffered I/O)
- Explains flame graph interpretation (width, height, colors, interactivity)
- Compares different profile types and their overhead characteristics
- Provides complete workflow from profiling to visualization

**Requirements:** JDK 11+ (for JFR support), port 9080 available, ~500MB disk space

This example is the recommended starting point for learning how to use the Descartes profiler effectively. See `src/main/java/com/bitsapplied/descartes/example/profiler/README.md` for detailed documentation.

## MCP Client Configuration

The repository includes a robust TCP adapter client in `/config/mcp/` that enables Claude Desktop (or other MCP clients) to connect to the Descartes MCP server:

### Files in /config/mcp/

- **mcp-tcp-adapter.js**: Node.js TCP adapter that bridges MCP clients to the TCP-based Descartes server
  - Handles automatic reconnection with exponential backoff
  - Queues messages during disconnections
  - Health monitoring with periodic pings
  - Full MCP protocol compliance for reconnections
  
- **mcpservers.json**: Example configuration for Claude Desktop
  - Configure this file with the correct path to mcp-tcp-adapter.js
  - Default configuration connects to localhost:9080
  
- **README-adapter.md**: Comprehensive documentation of the TCP adapter features
  
- **test-adapter-robustness.sh**: Test script to validate adapter reliability
- **test-improved-adapter.sh**: Additional adapter testing
- **test-mcp-handshake.js**: MCP protocol handshake testing

### Setting up Claude Desktop Integration

1. Copy the mcpservers.json to your Claude Desktop configuration directory
2. Update the path in mcpservers.json to point to the actual location of mcp-tcp-adapter.js:
   ```json
   "args": ["/absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js"]
   ```
3. Start the Descartes MCP server: `mvn exec:java`
4. The adapter will automatically connect and reconnect as needed

### TCP Adapter Features

- **Infinite reconnection**: Never gives up trying to connect
- **Message queuing**: Buffers messages during disconnections
- **Health monitoring**: Detects and recovers from stale connections
- **Configurable timeouts**: All delays and intervals can be customized via environment variables

## MCP Tool Usage from Claude

When calling Descartes MCP tools through Claude's interface, understand these parameter passing behaviors:

### Parameter Type Handling

**String Parameters** - Work as expected:
```python
mcp__morpheus__thread_analyzer(
    operation="thread_list",
    sort_by="cpu_time",
    name_pattern="pool-.*"
)
```

**Array Parameters** - Pass as simple values, NOT JSON arrays:
```python
# ✅ CORRECT - Single value
state_filter="RUNNABLE"

# ✅ CORRECT - Multiple values (if tool supports it)
state_filter=["RUNNABLE", "BLOCKED"]  # Claude handles the array conversion

# ❌ WRONG - JSON string literal
state_filter='["RUNNABLE"]'  # Results in: "No enum constant java.lang.Thread.State.[\"RUNNABLE\"]"
```

**Numeric Parameters** - May be coerced to strings by MCP layer:
- Tool implementations use `getIntParam()` to handle both Number and String
- You can pass numbers normally: `max_results=10`
- If you get "must be a number" error, the tool needs to add string handling

### Progressive Testing Approach

When using a new MCP tool:

1. **Start simple** - Test with minimal/no parameters first
```python
thread_analyzer(operation="deadlocks")  # No extra params
```

2. **Add one parameter at a time**
```python
thread_analyzer(operation="thread_list")  # Basic list
thread_analyzer(operation="thread_list", sort_by="cpu_time")  # Add sorting
thread_analyzer(operation="thread_list", sort_by="cpu_time", state_filter="RUNNABLE")  # Add filter
```

3. **Use alternatives when arrays fail** - Try named parameters:
```python
# If thread_ids array doesn't work:
thread_analyzer(operation="thread_inspect", thread_names="main")  # Use names instead
```

### Common Tool Workflows

**Thread analysis (`thread_analyzer`)**
- `thread_list` – Lightweight overview without stacks. Supports `sort_by` (`name`, `cpu_time`, `blocked_time`), `descending`, `state_filter`, `name_pattern`, `max_results`.  
  ```python
  thread_analyzer(operation="thread_list", sort_by="cpu_time", descending=true, max_results=25)
  ```
- `thread_search` – Find threads matching filters (`name_contains`, `state_in`, `daemon`, `min_cpu_time_ms`). Add `include_details=true` for full stacks (respect `max_threads_per_inspect`).  
  ```python
  thread_analyzer(
      operation="thread_search",
      name_contains="pool-",
      state_in=["RUNNABLE", "BLOCKED"],
      min_cpu_time_ms=500,
      include_details=true,
      max_stack_depth=15
  )
  ```
- `thread_inspect` – Deep dive into specific threads by `thread_ids` or `thread_names`. Honors flags: `include_stack`, `include_locks`, `include_monitors`, `include_synchronizers`, `filter_stack_pattern`.  
  ```python
  thread_analyzer(
      operation="thread_inspect",
      thread_names=["main", "Reference Handler"],
      include_stack=true,
      include_locks=true,
      max_stack_depth=20
  )
  ```
- `deadlocks` – Quick deadlock detection with participating thread details.  
  ```python
  thread_analyzer(operation="deadlocks")
  ```
- `thread_dump` – Full text dump with **intelligent truncation** and **importance-based prioritization**. Automatically prioritizes BLOCKED threads, high CPU threads, and application threads while filtering JVM system threads. **Guaranteed size limits** prevent overwhelming responses.

  **Smart Truncation Features:**
  - Importance scoring: BLOCKED (+100), high CPU (+75), contention (+80), non-daemon (+30)
  - Adaptive strategies: Behavior adjusts based on thread count (<20, 20-50, 50-100, >100)
  - Progressive detail reduction: Reduces stack depth/locks when approaching size limit
  - Auto-excludes JVM system threads when >50 threads (configurable)
  - Rich metadata: Explains what was filtered and why

  **Key Parameters:**
  - `smart_truncation`: Enable intelligent prioritization (default: true, set to false for old behavior)
  - `importance_threshold`: Minimum score for inclusion (default: 0, use 25 for high-value only, 50 for critical)
  - `exclude_jvm_threads`: "auto" (default), true, or false
  - `max_threads`: Hard limit on thread count (e.g., 20 for top 20 threads)
  - `name_pattern`, `state_filter`, `filter_stack_pattern`: User filters applied first
  - `max_stack_depth`: Stack frames per thread (default: 50, auto-reduced by strategy)

  **Basic usage** (automatic smart truncation):
  ```python
  thread_analyzer(operation="thread_dump")
  ```

  **Filtered usage** (narrow scope):
  ```python
  thread_analyzer(
      operation="thread_dump",
      name_pattern="^pool-.*",
      state_filter=["BLOCKED", "WAITING"],
      max_stack_depth=40
  )
  ```

  **Top N threads** (importance-ranked):
  ```python
  thread_analyzer(
      operation="thread_dump",
      max_threads=20,  # Show top 20 by importance score
      importance_threshold=25  # Only high-value threads
  )
  ```

  **Disable smart truncation** (backward compatibility):
  ```python
  thread_analyzer(
      operation="thread_dump",
      smart_truncation=false
  )
  ```

  **Response includes rich metadata:**
  - `metadata.collection`: Thread counts at each filtering stage
  - `metadata.truncation`: Strategy used, size limits, detail reductions
  - `metadata.exclusion_breakdown`: Why threads were excluded
  - `metadata.filters_applied`: Audit trail of all filtering decisions
  - `metadata.recommendations`: Actionable suggestions for refinement
  - `metadata.included_threads_summary`: Top threads for quick triage (name, state, importance score, CPU time, blocked time)

**Exception analysis (`exception_analysis`)**
- `get_recent` – Last *N* exceptions (default 10, max 50).  
  ```python
  exception_analysis(operation="get_recent", count=15)
  ```
- `get_last` – Most recent exception with parsed class/message plus raw text.  
  ```python
  exception_analysis(operation="get_last")
  ```
- `stats` – Aggregate counts by exception type.  
  ```python
  exception_analysis(operation="stats")
  ```
- `clear` – Purge the in-memory exception buffer.  
  ```python
  exception_analysis(operation="clear")
  ```

### Debugger Tool Quick Reference

> **Reminder:** Claude learns the exact JSON schema via `tools/list`. Use the notes below for workflow guidance, preconditions, and response shapes—not as a duplicate schema.

**Session control (`debugger_session`)**
- Operations: `start`, `stop`, `status`, `threads`, `suspend`, `resume`, `resume_all`
- Always check `status` before starting a second session:  
  ```python
  debugger_session(operation="status")
  debugger_session(operation="start", jdwp_timeout=10000)
  ```
- `suspend` / `resume` require a `thread_id` from `threads`

**Breakpoints (`debugger_breakpoints`)**
- Operations: `set`, `remove`, `remove_all`, `list`, `enable`, `disable`
- `set` needs `class_name` and `line_number`; optional `condition`
- `list` returns full breakpoint metadata including IDs for later removal

**Thread inspection (`debugger_threads`)**
- Operations: `list`, `inspect`, `suspend`, `resume`, `resume_all`
- `list` supports filters like `state_filter`, `name_pattern`
- `inspect` emits detailed thread info (stack summary, suspension state)

**Stepping (`debugger_step`)**
- Operations: `step_over`, `step_into`, `step_out`
- Requires a suspended thread; verify via `debugger_threads(list, suspended_only=true)`
- Returns a synchronous payload with:
  - `location` → `{class, method, line, source_path}`
  - `event_payload` → raw `debugger.step_complete` data
  - `duration_ms`, `completed_at`, and the original timeout used  
  ```python
  debugger_step(operation="step_over", thread_id=thread.id, timeout_ms=15000)
  ```

**Variable inspection (`debugger_variables`)**
- Operations: `get_variables`, `get_child_variables`, `get_static_fields`
- `get_variables` returns locals plus a `variableReference` for expandable values
- Use that reference with `get_child_variables(variable_reference=...)`
- Graph expansion is lazy: nothing is fetched unless you request a reference

**Stack traces (`debugger_stack_trace`)**
- Operations: `capture`, `capture_filtered`, `get_frame`, `get_current_frame`
- `capture_filtered` accepts `exclude_patterns` to skip library frames
- Responses include frame metadata plus source locations

**Watch expressions (`debugger_watch`)**
- Operations: `add`, `remove`, `remove_all`, `list`, `enable`, `disable`, `evaluate`
- Watches evaluate when the thread is suspended; each result reports value, strategy, and whether it changed since the last evaluation

**Expression evaluation (`debugger_evaluate`)**
- Operation: `evaluate`
- Requires a suspended thread/frame; returns `{result, strategy, duration_ms}`
- Use for one-off calculations that aren’t tied to persistent watches


### Troubleshooting MCP Calls

**Error: "Parameter required"** but you provided it
- Check if you're using the right parameter name (e.g., `thread_names` vs `thread_ids`)
- Some tools expect specific alternatives (names vs IDs)

**Error: "No enum constant"** with array syntax in message
- You're passing a JSON string instead of letting Claude handle the array
- Use simple values or proper array syntax (not quoted JSON)

**Error: "must be a number, but got String"**
- MCP layer converted your number to a string
- Report this - tool should handle both types
- Try passing as a number anyway (error message may be misleading)

## For Projects Using Descartes as a Dependency

If you're integrating Descartes MCP into your Java application, add the following to your project's CLAUDE.md to ensure Claude can effectively use Descartes for debugging and development:

### Quick Integration

1. **Add Descartes dependency** to your `pom.xml`:
```xml
<dependency>
    <groupId>com.bitsapplied.descartes</groupId>
    <artifactId>descartes-mcp</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

2. **Copy the Descartes section** from `CLAUDE-SECTION.md` into your project's CLAUDE.md

3. **Initialize Descartes** in your application (see SimpleMCPServerExample)

4. **Configure MCP client** to connect to your Descartes server port

### What to Tell Claude About Your Descartes Integration

Your CLAUDE.md should specify:
- **Port number** where Descartes MCP server runs (default: 9080)
- **Context map keys** - what application objects are available in the context
- **Custom tools** if you've added any beyond the built-in ones
- **Security boundaries** - what operations are safe in your environment
- **Environment-specific configs** - different settings for dev/staging/prod

### Example CLAUDE.md Addition for Your Project

```markdown
## Runtime Debugging with Descartes MCP

This application has Descartes MCP integrated on port 9080 for runtime introspection.

### Available Context Objects
- `context.get("dataSource")` - Main database connection
- `context.get("userService")` - User management service  
- `context.get("cache")` - Application cache manager
- `context.get("config")` - Runtime configuration

### Debugging Priority
When investigating issues, ALWAYS:
1. First check if Descartes is running: `lsof -i :9080`
2. Use Descartes tools to inspect runtime state before suggesting code changes
3. Test hypotheses with JShell before implementing fixes

### Safe Operations
- Read-only inspection of all objects
- Querying repositories and services
- Forcing garbage collection for memory analysis
- Changing log levels temporarily

### Restricted Operations  
- DO NOT modify production database connections
- DO NOT change security settings via JShell
- DO NOT expose sensitive data in JShell output
```
