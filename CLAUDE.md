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
# Build and test
mvn clean compile
mvn test                                    # Excludes concurrency/hot-reload tests
mvn test -Pconcurrency-tests                # Concurrency tests only
mvn test -Phot-reload-tests                 # Hot reload tests only
mvn test -Pall-tests                        # All tests

# Run examples
mvn exec:java                               # Standard mode
mvn compile exec:exec -Prun-with-agent      # Hot reload mode (recommended)
./run-with-hotreload.sh                     # Hot reload script

# Package
mvn clean package
```

## Test Environment Management

**CRITICAL**: Interrupted Maven tests leave zombie `surefirebooter` processes that cause port conflicts (9080) and test failures.

**Always clean before testing:**
```bash
pkill -9 -f surefirebooter 2>/dev/null; mvn test
pkill -9 -f surefirebooter 2>/dev/null; mvn test -Pall-tests
```

**For Claude Code Agents**: You MUST check and kill leftover processes before any `mvn test` command.

## Architecture

### Core Components

**MCPServer** (`com.bitsapplied.descartes.MCPServer`): JSON-RPC server managing connections on port 9080 and routing requests to tools/resources.

**Tools** (`com.bitsapplied.descartes.tools.*`): Callable functions implementing `MCPTool`:
- `JShellTool`: Interactive Java REPL with 30s timeout (configurable). Uses `JShell.stop()` for timeout protection. Limitations: may not stop I/O-blocked code or native operations.
- `JShellAsyncTool`: Fire-and-poll REPL. `start` submits snippets to a background executor, `status` returns completion/error payloads, `cancel` aborts long-running snippets. Pairs with `debugger_events` when the debugger needs to wait on breakpoints.
- `JShellSessionTool`: Session lifecycle management
- `ObjectInspectorTool`: Deep object inspection without code execution
- `HotClassReloadTool`: Runtime class redefinition (requires `-javaagent`)
- `ProcessInspectorTool`: Process and thread information
- `SystemMonitoringTool`: System metrics
- `ThreadAnalyzerTool`: Thread state and deadlock detection
  - **Operations**: `ThreadListOperation`, `ThreadInspectOperation`, `ThreadSearchOperation`, `DeadlockDetectionOperation`, `ThreadDumpOperation`
  - **Filters**: `StateFilter`, `NamePatternFilter`, `CpuTimeFilter`, `DaemonFilter` chained via `FilterChain`
  - **Builders**: `ThreadInfoBuilder` for fluent thread info construction
  - **Patterns**: Strategy (operations), Chain of Responsibility (filters), Builder (thread info)
- `MemoryAnalyzerTool`: Memory usage analysis
- `ExceptionAnalysisTool`: Exception tracking and statistics
- `LoggingIntegrationTool`: Log4j2 integration for log capture
- **Debugger Tools** (`com.bitsapplied.descartes.debugger.*`): Full JDI-based debugger
  - `DebuggerSessionTool`, `DebuggerBreakpointsTool`, `DebuggerThreadsTool`, `DebuggerStepTool`, `DebuggerVariablesTool`, `DebuggerStackTraceTool`, `DebuggerWatchTool`, `DebuggerEvaluateTool`
  - `DebuggerEventsTool`: Poll buffered debugger notifications. Use `wait` (blocking with timeout) or `fetch` to drain queued events.
- **Profiler Tools** (`com.bitsapplied.descartes.profiler.tools.*`): JFR-based profiling (0.5%-2% overhead)
  - `ProfilerStartTool`, `ProfilerStopTool`, `ProfilerHotspotsTool`, `ProfilerCallTreeTool`, `ProfilerListTool`, `ProfilerExportTool`
  - Profile types: `cpu` (default, ~1%), `allocation`, `comprehensive` (~2%), `lightweight` (~0.5%)
  - Workflow: Start → Auto-stop → Analyze hotspots → Call tree → Export flame graph

**Resources** (`com.bitsapplied.descartes.resources.*`): Read-only data via `MCPResource`:
- `ClasspathResource`, `SystemPropertiesResource`, `MetricsResource`, `ThreadDumpResource`, `MBeanResource`, `ApplicationContextResource`

**Hot Reload Subsystem** (`com.bitsapplied.descartes.hotreload.*`): Runtime class redefinition
- Components: `HotReloadAgent`, `HotReloadService`, `ClassLoadTracker`, `ClassStructureAnalyzer` (uses ASM)
- Requires: `-javaagent:descartes-mcp-jar-with-dependencies.jar`

**Profiler Subsystem** (`com.bitsapplied.descartes.profiler.*`): JFR-based profiling (JDK 11+)
- Components: `ProfilerService`, `ProfilerSettings`, `JFRRecorder`, `JFRParser`, `CallTreeBuilder`, `ProfileStore`, `FlameGraphExporter`
- Flame graphs: Interactive HTML with zoom/search, similar to Datadog/Honeycomb

**Context Map**: `Map<String, Object>` for sharing application objects between tools/resources without tight coupling.

### Key Design Patterns

- **Generic Context**: Tools access app objects via `Map<String, Object>` context
- **Session Management**: JShell sessions with configurable timeouts and isolation
- **Resource Registry**: URI-based access (`app://classpath`, `app://metrics`)
- **Strategy Pattern**: Thread operations as pluggable strategies
- **Chain of Responsibility**: Thread filters chained together
- **Builder Pattern**: Fluent APIs for complex object construction

## Maven Profiles

**Testing**: `mvn test` (default, fast), `-Pconcurrency-tests`, `-Phot-reload-tests`, `-Pall-tests`
**Runtime**: `-Prun-with-agent` (hot reload enabled, continuous mode)
**Build**: `-Peclipse-m2e` (Eclipse IDE)

**Important**: Keep `-XX:+EnableDynamicAgentLoading` and `--add-opens` flags when using `-javaagent` for JDK 17+ JPMS compatibility.

## Testing Approach

JUnit 5 with separate test profiles. Default excludes concurrency/hot-reload for faster feedback. Hot reload tests use ASM for bytecode manipulation.

## Java Version

Minimum: Java 16 (records, text blocks, `stream.toList()`)
Configured: Java 23 for optimal performance

## Code Review Guidelines

When reviewing code, prioritize **accuracy over volume**:

1. **Trace complete control flow** - Read setup AND teardown, check all code paths before claiming leaks/races
2. **Read test infrastructure** - Check base classes, `@BeforeAll/@AfterAll`, helper methods
3. **Verify claims with actual code** - Never assume based on patterns; read the specific lines
4. **Understand context** - `Thread.sleep()`, static fields, and "magic numbers" may be intentional
5. **Distinguish test types** - API tests, integration tests, behavior tests, and E2E tests are all valid
6. **Acknowledge corrections** - When wrong, explain what you missed and update your model
7. **Conservative severity** - Critical = data corruption/security, not style issues
8. **Positive recognition** - Acknowledge good practices

**Goal**: Accuracy and helpfulness, not finding issues. Zero-issue reviews with deep understanding beat 20 false alarms.

## Integration Points

To integrate Descartes into your application:

1. Create `Map<String, Object>` context with app objects
2. Instantiate `MCPServer` with settings and context
3. Register desired tools and resources
4. Start server on chosen port
5. Add shutdown hooks

### SimpleMCPServerExample

`com.bitsapplied.descartes.example.SimpleMCPServerExample` - Comprehensive example on port 9080
- Registers all tools (JShell, monitoring, debugging, profiling) and resources
- Smart mode detection: interactive (terminal) vs continuous (IDE/background)
- Mode override: `mvn exec:java -Ddescartes.continuous=true`

**Log4j2 Configuration**: For `LoggingIntegrationTool`, configure `InMemoryAppender` in `log4j2.properties`:
```properties
packages = com.bitsapplied.descartes.util
appender.inMemory.type = InMemoryAppender
appender.inMemory.name = INMEMORY
appender.inMemory.maxBufferSize = 500
rootLogger.appenderRefs = console, inMemory
rootLogger.appenderRef.inMemory.ref = INMEMORY
```

### DebuggerWorkflowExample

`com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample` - Complete debugger demo with AI-assisted debugging scenarios

**Run**: `./run-debugger-demo.sh` (auto-builds if needed, includes all necessary JVM flags)
**Interactive mode**: `./run-debugger-demo.sh --interactive`

Demonstrates autonomous debugging workflow: Claude sets breakpoints, steps through code, inspects variables, evaluates expressions, and finds bugs based on high-level problem descriptions.

**Scenarios**: Basic debugging, bug hunting, data structures, concurrency, exceptions, call stacks

See `src/main/java/com/bitsapplied/descartes/example/debugger/README.md` for details.

**Debugger orchestration pattern** (agents should follow):
1. `debugger_session` → start (supply JDWP host/port if remote).
2. `debugger_breakpoints` → set desired breakpoints.
3. `jshell_async start` (or other async trigger tool) → kick off workload; capture returned `task_id`.
4. `debugger_events wait` → block (with timeout) until a breakpoint event arrives; repeat if timeout.
5. On event: use `debugger_threads`, `debugger_variables`, `debugger_stacktrace`, etc., then `debugger_session resume`/`resume_all`.
6. `jshell_async status` → poll for snippet completion (optional `cancel` if hung).
7. Loop steps 4–6 for additional hits.

### ProfilerWorkflowExample

`com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample` - Complete profiler demo with realistic workloads

**Run**: `./run-profiler-demo.sh` (auto-builds if needed)
**Interactive mode**: `./run-profiler-demo.sh --interactive`
**Output**: `./profiler-demo-output/`

Includes workload generators: `ComputationWorkload`, `AllocationWorkload`, `ConcurrencyWorkload`, `IOWorkload`

See `src/main/java/com/bitsapplied/descartes/example/profiler/README.md` for details.

## Descartes Operational Modes

Descartes supports two operational modes for different deployment scenarios. Understanding these modes helps you choose the right approach for your use case.

### Mode 1: Embedded with Local Target

**Deployment:** Descartes JAR embedded in your application's classpath, debugging external process on same machine.

**When to use:**
- ✅ Local development with full control
- ✅ Need comprehensive tooling (debugging + REPL + profiling + monitoring)
- ✅ Hot-reload capabilities required
- ✅ Logging and exception tracking integration needed
- ✅ Single-machine deployment acceptable

**Tools Available:** All 20+ tools (debugger, JShell, hot-reload, profiling, monitoring, logging, exceptions)

**Example:**
```java
// In your application
Map<String, Object> context = new HashMap<>();
MCPServer server = new MCPServer(settings, 9080, context);
// Register all tools
server.registerTool(new DebuggerSessionTool(...));
server.registerTool(new JShellTool(...));
// ... register all tools ...
server.start();
```

### Mode 2: Standalone Remote Proxy

**Deployment:** Descartes runs as separate standalone process, connects to remote JVM via JDWP.

**When to use:**
- ✅ Debugging remote servers (staging, test, production)
- ✅ Debugging containerized apps (Docker, Kubernetes)
- ✅ Cannot modify target application's classpath
- ✅ Minimal footprint in target required (pure JDWP, no Descartes JAR)
- ✅ Debugging third-party or legacy applications

**Tools Available:** 11 JDWP-compatible tools (debugger_*, thread_analyzer, object_inspector)

**Launch:**
```bash
# Start proxy connecting to remote target
./run-remote-proxy.sh --jdwp-host staging.example.com --jdwp-port 5005

# Or with Maven
mvn compile exec:exec -Prun-remote-proxy \
    -Ddescartes.jdwp.host=staging.example.com \
    -Ddescartes.jdwp.port=5005
```

### Quick Decision Matrix

```
┌──────────────────────────────┬─────────────────────┬──────────────────────┐
│ Scenario                     │ Embedded Mode       │ Remote Proxy Mode    │
├──────────────────────────────┼─────────────────────┼──────────────────────┤
│ Local development            │ ✅ Recommended      │ ⚠️ Possible          │
│ Remote debugging             │ ❌ Not applicable   │ ✅ Recommended       │
│ Docker/Kubernetes            │ ⚠️ Possible         │ ✅ Recommended       │
│ Need JShell REPL             │ ✅ Available        │ ❌ Not available     │
│ Need hot-reload              │ ✅ Available        │ ❌ Not available     │
│ Need profiling               │ ✅ Available        │ ❌ Not available     │
│ Pure debugging only          │ ✅ Available        │ ✅ Available         │
│ Modify target classpath      │ ✅ Required         │ ❌ Not required      │
│ Target footprint             │ +10-20MB            │ Zero (separate)      │
└──────────────────────────────┴─────────────────────┴──────────────────────┘
```

### Tool Availability Summary

| Tool Category | Embedded Mode | Remote Proxy Mode |
|---------------|---------------|------------------|
| **Debugging** (debugger_*, 8 tools) | ✅ Full support | ✅ Full support |
| **Thread Analysis** (thread_analyzer) | ✅ Full support | ✅ Full support |
| **Object Inspection** (object_inspector) | ✅ Full support | ✅ Full support |
| **JShell REPL** (jshell_*, 3 tools) | ✅ Available | ❌ Not available* |
| **Hot Reload** (hot_reload_classes) | ✅ Available | ❌ Not available* |
| **System Monitoring** (system_monitoring) | ✅ Available | ❌ Limited* |
| **Memory Analysis** (memory_analyzer) | ✅ Available | ❌ Limited* |
| **Exception Tracking** (exception_analysis) | ✅ Available | ❌ Not available* |
| **Logging Integration** (logging_integration) | ✅ Available | ❌ Not available* |
| **Profiling** (profiler_*, 5 tools) | ✅ Available | ❌ Not available* |

**\* Why not available remotely?** These tools require in-process access (JShell instance, Java agent, JMX, Log4j2 appender, JFR) which JDWP does not provide. See [DEBUGGER.md](DEBUGGER.md#why-some-tools-require-in-process-access) for technical details.

### Architecture Comparison

**Embedded Mode:**
```
┌────────────────────────────────────┐
│  Your Application Process          │
│  ┌───────────┐    ┌─────────────┐ │
│  │ Descartes │ →  │ Target JVM  │ │
│  │ (MCP)     │JDWP│ (your code) │ │
│  └───────────┘    └─────────────┘ │
└────────────────────────────────────┘
```

**Remote Proxy Mode:**
```
┌─────────────┐  MCP   ┌──────────────┐  JDWP  ┌──────────────┐
│ MCP Client  │◄──────►│   Descartes  │◄──────►│  Target JVM  │
│  (Claude)   │  9090  │     Proxy    │ Network│  (any host)  │
└─────────────┘        └──────────────┘        └──────────────┘
```

### Configuration for Remote Proxy Mode

**Environment Variables:**
```bash
export DESCARTES_JDWP_HOST=staging.example.com
export DESCARTES_JDWP_PORT=5005
export DESCARTES_MCP_PORT=9090
./run-remote-proxy.sh
```

**Config File** (`proxy-config.json`):
```json
{
  "jdwpHost": "staging.example.com",
  "jdwpPort": 5005,
  "mcpPort": 9090,
  "jdwpTimeout": 10000,
  "reconnectEnabled": true
}
```

**Command Line:**
```bash
./run-remote-proxy.sh \
    --jdwp-host staging.example.com \
    --jdwp-port 5005 \
    --mcp-port 9090
```

### Detailed Documentation

- **Embedded Mode Examples:** See `SimpleMCPServerExample`, `DebuggerWorkflowExample`
- **Remote Proxy Guide:** See [doc/MCPRemoteDebugProxy.md](doc/MCPRemoteDebugProxy.md) for comprehensive setup, configuration, connection patterns, and troubleshooting
- **Technical Reference:** See [DEBUGGER.md#understanding-descartes-debugger-modes](DEBUGGER.md#understanding-descartes-debugger-modes) for architecture details and tool availability matrix
- **Workflow Patterns:** See [doc/debugger-workflow.md#architecture-proxy-vs-embedded-mode](doc/debugger-workflow.md#architecture-proxy-vs-embedded-mode) for MCP integration patterns

## MCP Client Configuration

`/config/mcp/` contains TCP adapter for Claude Desktop integration:

**Setup**:
1. Copy `mcpservers.json` to Claude Desktop config directory
2. Update path to `mcp-tcp-adapter.js`
3. Start Descartes: `mvn exec:java`

**Features**: Infinite reconnection, message queuing, health monitoring, configurable timeouts

## MCP Tool Usage from Claude

### Parameter Handling

**Arrays**: Pass as values, NOT JSON strings
```python
state_filter="RUNNABLE"              # ✅ Single value
state_filter=["RUNNABLE", "BLOCKED"]  # ✅ Multiple values
state_filter='["RUNNABLE"]'           # ❌ JSON string → error
```

**Progressive testing**: Start simple (no params) → Add one param at a time → Use alternatives if arrays fail

### Thread Analysis Tool

**`thread_analyzer`** operations:
- `thread_list` - Lightweight overview without stacks. Params: `sort_by`, `descending`, `state_filter`, `name_pattern`, `max_results`
- `thread_search` - Find threads by criteria. Params: `name_contains`, `state_in`, `daemon`, `min_cpu_time_ms`, `include_details`
- `thread_inspect` - Deep dive by `thread_ids` or `thread_names`. Flags: `include_stack`, `include_locks`, `include_monitors`, `include_synchronizers`
- `deadlocks` - Quick deadlock detection
- `thread_dump` - Full dump with **smart truncation**:
  - Importance scoring: BLOCKED (+100), high CPU (+75), contention (+80)
  - Adaptive strategies based on thread count
  - Auto-excludes JVM threads when >50 threads
  - Params: `smart_truncation` (default: true), `importance_threshold`, `exclude_jvm_threads`, `max_threads`, `max_stack_depth`
  - Metadata: collection stats, truncation details, exclusion breakdown, recommendations

### Exception Analysis Tool

**`exception_analysis`** operations:
- `get_recent` - Last N exceptions (default 10, max 50)
- `get_last` - Most recent exception with parsed details
- `stats` - Aggregate counts by type
- `clear` - Purge buffer

### Debugger Tool Workflow

**Autonomous debugging approach**:
1. Check/start session (`debugger_session`)
2. Set strategic breakpoints (`debugger_breakpoints`)
3. Execute code and detect suspended threads (`debugger_threads` with `suspended_only=true`)
4. Chain inspections (`debugger_variables`, `debugger_watch`, `debugger_evaluate`, `debugger_step`)
5. Synthesize findings and suggest fixes

**Key tools**: `debugger_session`, `debugger_breakpoints`, `debugger_threads`, `debugger_step`, `debugger_variables`, `debugger_stack_trace`, `debugger_watch`, `debugger_evaluate`

See `src/main/java/com/bitsapplied/descartes/example/debugger/README.md` for examples.

### Profiler Tool Workflow

1. `profiler_start` - Start with duration (10-300s) and type (`cpu`, `allocation`, `comprehensive`, `lightweight`)
2. `profiler_stop` - Force-stop if needed
3. `profiler_hotspots` - Find top methods by CPU/memory %
4. `profiler_call_tree` - Analyze call hierarchies
5. `profiler_export` - Export as JSON/text/flamegraph (interactive HTML)

**Flame graph features**: Width = time spent, height = stack depth, colors = packages, interactive zoom/search

### Troubleshooting

- **"Parameter required"** → Check parameter name (`thread_names` vs `thread_ids`)
- **"No enum constant" with array** → Don't quote JSON; use proper array syntax
- **"must be a number, but got String"** → MCP coerced type; tool should handle both

## For Projects Using Descartes

Add to your `pom.xml`:
```xml
<dependency>
    <groupId>com.bitsapplied.descartes</groupId>
    <artifactId>descartes-mcp</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

In your CLAUDE.md, specify:
- Port number (default: 9080)
- Context map keys (available app objects)
- Custom tools beyond built-ins
- Security boundaries (safe/restricted operations)
- Environment-specific configs

Example:
```markdown
## Runtime Debugging with Descartes MCP

Descartes runs on port 9080. Context objects: `dataSource`, `userService`, `cache`, `config`

**Debugging Priority**: Check Descartes first (`lsof -i :9080`), inspect runtime state before code changes, test hypotheses with JShell.

**Safe**: Read-only inspection, repository queries, GC forcing, log level changes
**Restricted**: No production DB modifications, no security changes, no sensitive data exposure
```
