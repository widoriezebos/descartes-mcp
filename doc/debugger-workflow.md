# MCP Debugger Workflow

This guide describes the recommended sequence for debugging with Descartes over the Model Context Protocol (MCP). MCP
calls are synchronous, so breakpoint hits can block the request that triggered them. To keep the channel responsive we
pair asynchronous workload triggers with event polling, letting clients inspect suspended threads without deadlocking.

## Prerequisites

- Descartes MCP server running (embedded, standalone, or remote JDWP target).
- Debugger tools registered (`debugger_session`, `debugger_breakpoints`, `debugger_threads`, `debugger_variables`, …).
- Async trigger tool available (e.g., the `jshell_async` helper) that returns immediately with a `taskId`.
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

- **Timeout Discipline**: Always provide timeouts for `debugger_events wait` so clients can report “no breakpoint yet”
  and decide whether to retry or abort.
- **Remote Targets**: The pattern is identical for remote JVMs—only the JDWP connection parameters change when starting
  the session. Async triggers might call HTTP endpoints, enqueue jobs, or signal other infrastructure instead of
  running JShell snippets.
- **Macro Helpers**: You can build higher-level tools (e.g., `debugger_session resume_until_event`) on top of this flow
  to reduce client orchestration. Internally they still follow the same resume → wait → inspect loop.
- **Cancellation**: Provide cancellation (`jshell_async cancel taskId`) so agents can abandon hung workloads without
  tearing down the debug session.
- **Manual Triggers**: If a human or external system drives the workload, skip step 3. The agent sets breakpoints, waits
  for events, and the user exercises the application; the rest of the flow is unchanged.

This structure keeps Descartes responsive under MCP’s synchronous contract while supporting debugging of both embedded
and remote JVMs.
