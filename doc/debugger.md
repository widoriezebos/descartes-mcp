# Runtime Debugger

Descartes bundles an opinionated debugging stack that lives entirely inside the target JVM. The tooling is surfaced through MCP tools so AI assistants (or scripts) can drive the workflow remotely.

## Core Components

- **JShell REPL — `jshell_repl` (`JShellTool`)**  
  Runs code inside the host JVM, capturing stdout/stderr and persisting state per `session_id`. Evaluations are compiled against the live classpath that `JShellService` builds at startup.

- **Session Manager — `jshell_session_manager` (`JShellSessionTool`)**  
  Closes sessions, extends expiry windows, and enforces a configurable limit. Use it to clean up long-running sessions or adjust capacity during heavy debugging.

- **Object Inspector — `object_inspector` (`ObjectInspectorTool`)**  
  Evaluates expressions that must start with the configured context variable (`context` by default). Returns structured type information, field graphs, methods, and values. Uses the JShell runtime but refuses to run arbitrary expressions that are not rooted in the context map.

## Preparing Context

Every debugger feature relies on the shared `Map<String,Object>` passed to `MCPServer`. Provide any services, repositories, or singletons you want available in JShell or the inspector:

```java
Map<String, Object> context = new HashMap<>();
context.put("userService", userService);
context.put("config", appConfig);

MCPServer server = new MCPServer(new DefaultSettings(), 9080, context);
server.registerTool(new JShellTool(context));
server.registerTool(new JShellSessionTool(context));
server.registerTool(new ObjectInspectorTool(context));
```

If the tools are created without the same map, they cannot reach application objects and will fall back to plain JVM state.

## JShell Workflows

```json
{
  "name": "jshell_repl",
  "arguments": {
    "session_id": "story-42",
    "code": """
      var user = ((com.example.UserService) context.get("userService")).findById(7L);
      user.getEmail()
    """
  }
}
```

- `reset: true` clears the session before evaluation.
- `close_session: true` disposes after the call.
- `extend_expiry_minutes` prolongs idle timeouts for long investigations.

Session metadata is exposed via:

```json
{ "name": "jshell_session_manager", "arguments": { "action": "session_count" } }
```

Other actions: `close`, `extend_expiry`, `get_max_sessions`, `set_max_sessions`.

## Object Inspection

For defensive use, the inspector only executes expressions that begin with the configured context alias (`context` by default). Example:

```json
{
  "name": "object_inspector",
  "arguments": {
    "expression": "context.get(\"userService\")",
    "operation": "inspect",
    "include_private": true,
    "max_depth": 2
  }
}
```

Responses include:
- `type`, `simple_type`, `superclass`, `interfaces`
- `fields` with modifiers and nested values (respecting `max_depth`)
- `methods` with signatures when `operation` is `methods`
- `value` for rendering `toString()` safely

## Additional Diagnostics

- **Process snapshots — `process_inspector` (`ProcessInspectorTool`)**  
  Provides stack traces, thread summaries, and optional filtering by package or thread state.

- **Thread analysis — `thread_analyzer` (`ThreadAnalyzerTool`)**  
  Progressive disclosure design: start with `thread_list`, refine with `thread_search`, and finally inspect stacks via `thread_inspect`.

- **System monitoring — `system_monitoring` (`SystemMonitoringTool`)**  
  Combines CPU, memory, GC, and optional thread sampling to highlight resource pressure.

- **Heap perspective — `memory_analyzer` (`MemoryAnalyzerTool`)**  
  Reports pool usage, GC stats, and quick leak indicators.

- **Exception harvesting — `exception_analysis` (`ExceptionAnalysisTool`)**  
  Parses captured stack traces, deduplicates by root cause, and flags recent errors.

## Log Capture

`logging_integration` (`LoggingIntegrationTool`) synchronises with the custom `InMemoryAppender` defined in `com.bitsapplied.descartes.util`. Configure Log4j2 once per JVM:

```properties
packages = com.bitsapplied.descartes.util
appender.inMemory.type = InMemoryAppender
appender.inMemory.name = INMEMORY
appender.inMemory.maxBufferSize = 500
rootLogger.appenderRefs = console, inMemory
rootLogger.appenderRef.inMemory.ref = INMEMORY
```

Once configured, the tool can tail logs, change package-specific levels, and clear buffers on demand.

## Safety Checklist

- Restrict network access to the MCP server; debugger tools execute arbitrary code.
- Refresh or close JShell sessions after large changes to avoid stale state.
- When using hot reload, validate changes (`validateOnly: true`) before executing code that depends on the updated classes.
