# Debugger Guide

Descartes exposes a JDWP-powered debugger through Model Context Protocol so  agents (or scripts) can attach to a live JVM much like an IDE. This guide explains the architecture, tool coverage, and the operations provided by every `debugger_*` tool.

> ⚠️ **Security**: Debugger tools can execute arbitrary code, suspend threads, and inspect process memory. Enable them only in trusted development environments.

## Capabilities at a Glance

- Attach to any JVM that started with `-agentlib:jdwp=…` and drive it remotely.
- Manage suspension, stepping, breakpoints, and watch expressions.
- Inspect stack frames and variables with lazy expansion.
- Evaluate Java expressions via a hybrid Janino → JShell pipeline.
- Poll buffered events (breakpoint hits, step completion, exceptions) even though MCP has no push notifications.

## Requirements

- **JDK 11+** on the target JVM (JDWP ships with the JDK, not the JRE).
- Start the JVM with JDWP enabled, for example:
  ```
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
  ```
  HotSpot does **not** expose `Agent_OnAttach` for JDWP, so you cannot load it dynamically via the Attach API.
- **JDK 17+** targets also need reflective access:
  ```
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.util=ALL-UNNAMED
  ```
- Network reachability between Descartes and the JDWP host/port (`localhost` by default).

## Architecture Overview

```
MCP Client ──TCP──┐
                  │
              MCPServer
                  │ (MCP tools call DebuggerService)
                  ▼
        DebuggerService + JDWPConnector  ──JDWP──►  Target JVM
           │  state machine (CREATED→CONNECTING→READY↔SUSPENDED↔STEPPING→…)
           │  breakpoint manager, stepping controller, evaluation engine,
           │  variable extractor, watch manager, event bridge
```

- **DebuggerService** orchestrates sessions, maintains a well-defined state machine, and serialises JDI access through a single-threaded executor.
- **JDWPConnector / JDWPConnectionManager** manage the socket, enforce timeouts, and reconnect when necessary.
- **BreakpointManager**, **SteppingController**, **StackTraceInspector**, **VariableExtractor**, **HybridEvaluationProvider**, and **WatchExpressionManager** implement the behaviours exposed via MCP.
- **DebuggerEventQueue** buffers JDWP events so clients can poll them using `debugger_events`.

## Deployment Modes

| Aspect | Embedded Mode | Remote Proxy (`MCPRemoteDebugProxy`) |
|--------|---------------|--------------------------------------|
| Process model | Descartes runs inside the target JVM alongside your application code. | Descartes runs separately and connects to a remote JVM started with JDWP. |
| Tool coverage | Full catalogue (debugger suite, JShell, hot reload, profiling, monitoring, log files, resources, etc.). | JDWP-compatible set only: `debugger_*`, `thread_analyzer`, `object_inspector` (11 tools). |
| JDWP configuration | Auto-detected from local JVM flags. | Provide host/port explicitly when starting the session. |
| Primary use case | Local development and sidecars where you want full observability. | Remote hosts, containers, or shared environments where embedding Descartes is impossible. |
| Recommended launcher | `./run-with-hotreload.sh [--continuous]` | `./run-remote-proxy.sh --jdwp-host <host> --jdwp-port <port>` |

Both modes require the target JVM to start with JDWP. Neither mode can “self-debug” the process that hosts Descartes.

## Tool Catalogue

| Tool | Purpose | Key Operations |
|------|---------|----------------|
| `debugger_session` | Manage lifecycle: start/stop sessions, query status, and suspend/resume threads. | `start`, `stop`, `status`, `threads`, `suspend`, `resume`, `resume_all` |
| `debugger_threads` | List/filter threads and inspect a specific thread’s metadata. | `list`, `inspect`, `suspend`, `resume`, `resume_all` |
| `debugger_breakpoints` | Configure line breakpoints with optional conditions and suspend policies. | `set`, `remove`, `remove_all`, `list`, `enable`, `disable` |
| `debugger_step` | Control execution flow for a suspended thread. | `step_over`, `step_into`, `step_out` |
| `debugger_stacktrace` | Capture stack traces and inspect individual frames. | `capture`, `capture_filtered`, `get_frame`, `get_current_frame` |
| `debugger_variables` | Inspect locals, arguments, `this`, expandable objects, and static fields. | `get_variables`, `get_child_variables`, `get_static_fields` |
| `debugger_evaluate` | Evaluate expressions inside a suspended frame (Janino→JShell fallback). | `evaluate` |
| `debugger_watch` | Register expressions that auto-evaluate when execution suspends. | `add`, `remove`, `remove_all`, `list`, `enable`, `disable`, `evaluate` |
| `debugger_events` | Poll or wait for buffered debugger notifications. | `wait`, `fetch`, `clear` |
| `thread_analyzer` | Progressive disclosure thread analysis (JDWP aware). | `thread_list`, `thread_inspect`, `thread_search`, `deadlocks`, `thread_dump` |
| `object_inspector` | Evaluate expressions that start from the shared context map. | `inspect`, `fields`, `methods`, `type`, `value` |

> Proxy mode registers the debugger suite plus `thread_analyzer` and `object_inspector`; every other Descartes tool (JShell REPL, hot reload, profiler, monitoring, log files, exception analysis, etc.) requires embedded mode.

### Session Management — `debugger_session`

- `start`: Connects to JDWP. Options include `jdwp_timeout` (ms, default 5000), `stop_on_entry`, and `skip_patterns` to ignore library frames while stepping.
- `stop`: Gracefully tears down the session and resumes any suspended threads.
- `status`: Returns state (`READY`, `SUSPENDED`, etc.) plus the active configuration.
- `threads`: Snapshot of known threads with suspend counts and metadata.
- `suspend` / `resume` / `resume_all`: Control thread execution by ID.

### Thread Control — `debugger_threads`

- `list`: Filter by `state_filter`, `name_pattern`, or `suspended_only`.
- `inspect`: Detailed metadata for a single thread.
- `suspend`, `resume`, `resume_all`: Duplicates of the session commands for convenience.

### Breakpoints — `debugger_breakpoints`

- `set`: Provide `class_name` and `line_number`; optional `condition` and `suspend_policy` (`thread`, `all`, `none`).
- `list`: View IDs, hit counts, conditions, and enabled state.
- `enable`, `disable`, `remove`, `remove_all`: Maintain breakpoint lifecycle.

### Stepping — `debugger_step`

- Requires a suspended `thread_id`.
- `step_over`, `step_into`, `step_out` mirror IDE behaviour.
- `timeout_ms` bounds how long the tool waits for JDWP to report the new location (defaults to 10 s, clamps between 100 ms and 60 s).
- Responses include the resolved source location, thread info, and elapsed time.

### Stack Inspection — `debugger_stacktrace`

- `capture`: Full stack (default depth 100 frames).
- `capture_filtered`: Supply `exclude_patterns` to drop infrastructure packages.
- `get_frame`: Fetch a specific frame by index and return locals summary, source info, and method signature.
- `get_current_frame`: Convenience wrapper for the top frame.

### Variable Inspection — `debugger_variables`

- `get_variables`: Requires `thread_id` and `frame_index`. Returns locals, arguments, `this`, and synthetic slots, each with a lazily expandable `variable_reference`.
- `get_child_variables`: Expand complex objects (lists, maps, arrays, POJOs).
- `get_static_fields`: Inspect static members by `class_name`.
- Errors surface with `DebuggerErrorCode` entries (`THREAD_NOT_FOUND`, `THREAD_NOT_SUSPENDED`, `INVALID_FRAME`, etc.).

### Expression Evaluation — `debugger_evaluate`

- `evaluate`: Supply a `thread_id` or `thread_name`, optional `frame_index`, and the expression.
- The hybrid evaluator tries Janino first for single-expression snippets, then falls back to JShell for lambdas, helper methods, or multi-line code.
- Responses include the result, evaluation strategy, and execution time. Exceptions bubble up with structured error codes.

### Watch Expressions — `debugger_watch`

- `add`: Register an expression (with optional `display_name`); returns `watch_id`.
- `list`: Enumerate watches, last evaluation status, and enabled state.
- `enable`, `disable`, `remove`, `remove_all`: Maintain the watch list.
- `evaluate`: Force evaluation in the current suspend context.

### Event Polling — `debugger_events`

- `wait`: Block for up to `timeout_ms` (default 30 s) for the next event, optionally filtered by `types` and `thread_id`.
- `fetch`: Drain up to `max_events` (default 10, max 100) immediately.
- `clear`: Remove everything from the queue.
- Event payloads include the event type, timestamp, thread context, location, and metadata such as breakpoint IDs or watch results.

### Thread Analyzer & Object Inspector

- `thread_analyzer` mirrors the standalone tool: progressive disclosure (`thread_list` → `thread_search` → `thread_inspect`) with JDWP-aware stack collection.
- `object_inspector` evaluates expressions that begin with the configured context alias. In proxy mode the context only contains proxy metadata, so rely on `debugger_variables` for real application data; in embedded mode you can expose your own services in the context map.

## Expression Evaluation Pipeline

1. **Janino** compiles lightweight expressions quickly.
2. If Janino fails, **JShell** handles richer constructs (lambdas, helper methods, loops).
3. Both strategies execute inside the debuggee JVM with access to the suspended frame’s scope.
4. Failures return structured `DebuggerErrorCode` entries so clients can surface actionable errors.

## Event Flow

Debugger notifications use the following types:

- `debugger.breakpoint_hit`
- `debugger.step_completed`
- `debugger.exception_thrown`
- `debugger.watch_evaluated`
- `debugger.session_state_changed`

Each event is buffered until a client calls `debugger_events.wait` or `debugger_events.fetch`.

## Operational Guidance

- **Start JDWP at JVM launch**; HotSpot will not load it dynamically.
- **Stop sessions promptly** with `debugger_session stop` to release JDWP and clear breakpoints.
- **Resume threads** after inspection (`debugger_session resume_all`) to avoid leaving the application suspended.
- **Use skip patterns** during `start` to keep stepping responsive by ignoring library frames.
- **Embedded mode**: combine debugger tools with JShell, hot reload, and monitoring for full observability. **Proxy mode**: focus on the debugger suite.

## Troubleshooting

- `SESSION_NOT_ACTIVE`: Run `debugger_session start` and verify the JDWP host/port.
- `THREAD_NOT_SUSPENDED`: Suspend the thread before inspecting variables or evaluating expressions.
- `THREAD_NOT_FOUND`: Refresh the thread list—threads may exit between calls.
- `TIMEOUT`: Adjust `jdwp_timeout` (session start) or `timeout_ms` (stepping) for slow or remote targets.
- `Evaluation failed`: Ensure the expression is valid for the suspended frame; complex snippets may require fully qualified names or temporary helpers.

## Further Reading

- `src/main/java/com/bitsapplied/descartes/example/debugger/DebuggerWorkflowExample.java`
- `src/main/java/com/bitsapplied/descartes/example/debugger/README.md`
- [doc/MCPRemoteDebugProxy.md](MCPRemoteDebugProxy.md) for the remote proxy internals and configuration
- [doc/tools.md](tools.md) for exact schemas and response formats
