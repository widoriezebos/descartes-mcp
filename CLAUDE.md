# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Descartes MCP is a Java-based Model Context Protocol (MCP) server that provides deep introspection, monitoring, debugging, and REPL capabilities for Java applications. It enables AI assistants to interact with running Java processes through tools and resources.

**SECURITY NOTE**: The JShell tools provide arbitrary code execution capabilities. This server should only be used in development environments and never exposed to untrusted networks or users in production.

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

## Architecture

### Core Components

**MCPServer** (`com.bitsapplied.descartes.MCPServer`): Main server implementation that handles JSON-RPC protocol, manages client connections on a configurable port (default 9080), and routes requests to registered tools and resources.

**Tools** (`com.bitsapplied.descartes.tools.*`): Implement the `MCPTool` interface to provide callable functions:
- `JShellTool`: Interactive Java REPL with session management
- `JShellSessionTool`: Manages JShell sessions lifecycle
- `ObjectInspectorTool`: Deep object inspection without code execution
- `HotClassReloadTool`: Hot reload Java classes at runtime (requires agent mode)
- `ProcessInspectorTool`: Process and thread information
- `SystemMonitoringTool`: System metrics and monitoring
- `ThreadAnalyzerTool`: Thread state and deadlock detection
- `MemoryAnalyzerTool`: Memory usage analysis
- `ExceptionAnalysisTool`: Exception and error analysis
- `LoggingIntegrationTool`: Log4j2 integration for log capture
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

### Common Parameter Patterns

**thread_analyzer examples:**
```python
# List all threads (lightweight, no stacks)
thread_analyzer(operation="thread_list", sort_by="cpu_time")

# Filter by state
thread_analyzer(operation="thread_list", state_filter="RUNNABLE")

# Inspect specific thread by name
thread_analyzer(operation="thread_inspect", thread_names="main", include_stack=true)

# Detect deadlocks
thread_analyzer(operation="deadlocks")

# Filtered thread dump
thread_analyzer(operation="thread_dump", name_pattern="pool-.*", max_stack_depth=10)
```

**exception_analysis examples:**
```python
# Get recent exceptions
exception_analysis(operation="get_recent", count=10)

# Get statistics
exception_analysis(operation="stats")
```

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

### Making Claude Descartes-Aware

To maximize Claude's effectiveness with Descartes:

1. **Emphasize runtime-first debugging**: Tell Claude to check Descartes before suggesting code changes
2. **Document context objects**: List what's available through `context.get()`
3. **Specify security boundaries**: What's safe vs. restricted
4. **Include connection details**: Port, authentication if any
5. **Provide examples**: Show common debugging patterns for your application

### Integration Patterns

#### For Spring Boot Applications
```java
@Component
public class DescartesIntegration {
    @Autowired
    private ApplicationContext springContext;
    
    @PostConstruct
    public void initDescartes() {
        Map<String, Object> context = new HashMap<>();
        // Expose Spring beans to Descartes
        context.put("springContext", springContext);
        context.put("dataSource", springContext.getBean(DataSource.class));
        // ... register other beans
        
        MCPServer server = new MCPServer(settings, 9080, context);
        // ... configure and start
    }
}
```

#### For Standalone Applications
```java
public class MyApp {
    public static void main(String[] args) {
        // Initialize your application
        MyService service = new MyService();
        Repository repo = new Repository();
        
        // Create Descartes context
        Map<String, Object> context = new HashMap<>();
        context.put("service", service);
        context.put("repository", repo);
        context.put("app", MyApp.class);
        
        // Start Descartes (see SimpleMCPServerExample)
        startDescartes(context);
    }
}
```

### Benefits for Claude-Assisted Development

When Descartes is properly integrated and documented in CLAUDE.md:

1. **Faster debugging**: Claude can inspect runtime state immediately
2. **Accurate fixes**: Test solutions before code changes
3. **Better understanding**: Explore actual object relationships
4. **Reduced guesswork**: See real data and behavior
5. **Safe experimentation**: Test in JShell without code deployment

### See Also

- `CLAUDE-SECTION.md` - Complete template for your CLAUDE.md
- `SimpleMCPServerExample.java` - Reference implementation
- `/config/mcp/` - MCP client configuration examples