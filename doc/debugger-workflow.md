# MCP Debugger Workflow

This guide describes the recommended sequence for debugging with Descartes over the Model Context Protocol (MCP). MCP
calls are synchronous, so breakpoint hits can block the request that triggered them. To keep the channel responsive we
pair asynchronous workload triggers with event polling, letting clients inspect suspended threads without deadlocking.

## Architecture: Proxy vs Embedded Mode

Descartes debugging **always operates in proxy mode**: the debugger connects to a separate JVM via JDWP (Java Debug Wire
Protocol), similar to how IDE debuggers work. Understanding the two operational modes helps you choose the right deployment
pattern for your use case.

### Operational Modes

```
Embedded with Local Target              Standalone Remote Proxy
┌─────────────────────────────────┐     ┌────────────────┐  JDWP   ┌────────────────┐
│  Your Application Process       │     │  Descartes     │◄───────►│   Remote JVM   │
│  ┌──────────┐   JDWP  ┌────────┐│     │  Proxy         │ Network │   (any host)   │
│  │Descartes │◄───────►│Target  ││     │  (port 9090)   │         │   (port 5005)  │
│  │(port9080)│         │(5005)  ││     └────────────────┘         └────────────────┘
│  └──────────┘         └────────┘│
└─────────────────────────────────┘
```

**Key Architectural Constraint:**
Even in "embedded mode," Descartes cannot debug itself. HotSpot's JDWP agent lacks `Agent_OnAttach` support, preventing
dynamic attachment. The target JVM **must be launched** with `-agentlib:jdwp=...` from startup. See
[DEBUGGER.md](../DEBUGGER.md#why-the-debugger-works-this-way-agent_onattach-limitation) for technical details.

### Mode Comparison

| Aspect | Embedded with Local Target | Standalone Remote Proxy |
|--------|---------------------------|-------------------------|
| **Architecture** | Descartes bundled in app, debugs external process on same host | Standalone Descartes, connects to remote JDWP |
| **Deployment** | Add Descartes dependency to target app | Separate process, no dependency |
| **Target Requirements** | JDWP enabled at startup | JDWP enabled at startup only |
| **Network** | Localhost JDWP connection | Can connect across network |
| **MCP Port** | 9080 (default) | 9090 (default, avoids conflicts) |
| **Tool Availability** | All tools (20+ tools) | JDWP-compatible only (11 tools) |
| **Use Cases** | Local development, full observability | Remote debugging, containers, staging/prod |

### Tool Availability Matrix

Understanding which tools work in each mode is critical for planning debugging workflows:

| Tool Category | Via JDWP (Remote Proxy) | Via In-Process (Embedded) | Why |
|---------------|------------------------|---------------------------|-----|
| **debugger_\*** (8 tools) | ✅ Full support | ✅ Full support | JDI API over JDWP socket |
| **debugger_events** | ✅ Full support | ✅ Full support | Event queue via JDWP |
| **thread_analyzer** | ✅ Full support | ✅ Full support | JDI ThreadReference API |
| **object_inspector** | ✅ Full support | ✅ Full support | JDI ObjectReference API |
| **jshell_repl** | ❌ Not available | ✅ Available | Requires JShell instance in target process |
| **jshell_async** | ❌ Not available | ✅ Available | Same as jshell_repl |
| **hot_reload_classes** | ❌ Not available | ⚠️ Requires -javaagent | Needs Instrumentation API |
| **system_monitoring** | ❌ Limited | ✅ Full support | Needs JMX/local JVM access |
| **memory_analyzer** | ❌ Basic only | ✅ Full support | Needs MemoryMXBean access |
| **exception_analysis** | ❌ Not available | ✅ Available | Needs in-process log buffer |
| **logging_integration** | ❌ Not available | ✅ Available | Needs Log4j2 appender in target |
| **profiler_\*** (5 tools) | ❌ Not available | ✅ Available | Needs JFR access |

**Legend:**
- ✅ Full support - Complete functionality
- ⚠️ Limited/conditional - Requires additional setup
- ❌ Not available - Cannot work in this mode

**Why Some Tools Don't Work Remotely:**
- **JShell**: Requires a JShell interpreter instance running in the target JVM process
- **Hot Reload**: Requires Java agent (`-javaagent`) loaded in target for Instrumentation API
- **Monitoring/Profiling**: Need direct JMX access or local filesystem access (JFR recordings)
- **Logging/Exceptions**: Require custom appenders/handlers registered in target's logging framework

**Solution:** For workflows requiring unavailable tools in remote proxy mode, either:
1. Switch to embedded mode if you control the target deployment
2. Use alternative approaches (e.g., `debugger_evaluate` instead of `jshell_repl` for expression evaluation)
3. Access logs/metrics through the application's own endpoints/infrastructure

### When to Use Each Mode

**Choose Embedded with Local Target when:**
- Developing locally with full control over application startup
- Need comprehensive tooling beyond debugging (REPL, profiling, hot-reload)
- Want single-process simplicity (Descartes JAR in classpath)
- Require logging and exception tracking integration

**Choose Standalone Remote Proxy when:**
- Debugging applications on remote servers (staging, test, production)
- Debugging containerized applications (Docker, Kubernetes)
- Cannot modify target application's classpath or dependencies
- Want minimal footprint in target (pure JDWP, no Descartes JAR)
- Need to debug third-party or legacy applications

**Connection Examples:**

Embedded mode (auto-detect local JDWP):
```json
{
  "tool": "debugger_session",
  "operation": "start"
}
```

Remote proxy mode (explicit JDWP connection):
```json
{
  "tool": "debugger_session",
  "operation": "start",
  "host": "staging.example.com",
  "port": 5005,
  "jdwp_timeout": 10000
}
```

For comprehensive remote proxy setup, see [MCPRemoteDebugProxy.md](./MCPRemoteDebugProxy.md).

## Prerequisites

- Descartes MCP server running (either embedded with local target or standalone remote proxy).
- Debugger tools registered (`debugger_session`, `debugger_breakpoints`, `debugger_threads`, `debugger_variables`, …).
- Async trigger tool available (e.g., `jshell_async` for embedded mode) that returns immediately with a `taskId`.
- Event queue tool available (`debugger_events wait`/`fetch`) exposing breakpoint notifications from
  `DebuggerNotificationBroadcaster`.

## Core Workflow

1. **Start a debug session**  
   Call `debugger_session` with `operation: "start"` (optionally supplying JDWP host/port if you target a remote JVM).

2. **Set breakpoints**  
   Use `debugger_breakpoints set` for the locations you want to observe. Multiple breakpoints can be active at once.

3. **Kick off the workload asynchronously**  
   Invoke the async trigger tool (`jshell_async start`, custom runner, etc.). It MUST return immediately with a task
   identifier so the MCP connection stays free.

4. **Wait for breakpoint events**  
   Issue `debugger_events wait` (or `fetch` with a short timeout). The call blocks until an event arrives or the timeout
   elapses. If an event occurred before the wait call, it is drained from the queue on the first poll.

5. **Inspect the suspended thread**  
   Once `wait` returns, gather state while the target is suspended:
   - `debugger_threads` to list threads or locate the suspended thread id.
   - `debugger_stacktrace` for frame details.
   - `debugger_variables` / `debugger_evaluate` for local state and expressions.

6. **Resume execution**  
   Use `debugger_session resume` (single thread) or `debugger_session resume_all` when inspection is complete.

7. **Collect workload result**  
   Poll the async trigger (`jshell_async status taskId`) to learn whether it completed, timed out, or threw an error.
   Retrieve stdout/stderr or return payloads as needed.

Repeat steps 4–7 for additional breakpoint hits or continue the workflow by triggering new workloads.

## Notes and Variations

- **Timeout Discipline**: Always provide timeouts for `debugger_events wait` so clients can report "no breakpoint yet"
  and decide whether to retry or abort.
- **Remote Proxy Mode**: The workflow pattern is identical for remote JVMs—only the JDWP connection parameters change
  when starting the session (supply explicit `host` and `port`). Since `jshell_async` is unavailable remotely, async
  triggers might call HTTP endpoints, enqueue jobs, or signal other infrastructure to exercise the target application.
- **Macro Helpers**: You can build higher-level tools (e.g., `debugger_session resume_until_event`) on top of this flow
  to reduce client orchestration. Internally they still follow the same resume → wait → inspect loop.
- **Cancellation**: Provide cancellation (`jshell_async cancel taskId`) so agents can abandon hung workloads without
  tearing down the debug session.
- **Manual Triggers**: If a human or external system drives the workload, skip step 3. The agent sets breakpoints, waits
  for events, and the user exercises the application; the rest of the flow is unchanged.

This structure keeps Descartes responsive under MCP's synchronous contract while supporting debugging in both embedded
and remote proxy modes.
