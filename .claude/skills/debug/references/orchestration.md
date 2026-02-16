# Async Orchestration Pattern

## The Fundamental Constraint

MCP is synchronous: every tool call blocks until the server returns a response. If you trigger a workload synchronously (e.g., `jshell_repl`) and that workload hits a breakpoint, the breakpoint suspends the thread — which is the same thread handling the MCP call. The MCP call never returns. The channel is permanently deadlocked.

**The entire async pattern exists to prevent this deadlock.** You trigger the workload asynchronously (it returns immediately with a task ID), then poll for breakpoint events on the now-free MCP channel.

## Complete Async Trigger + Event Polling Loop

### Phase 1: Establish Baseline

Get a sequence cursor to avoid processing stale events from previous debugging sessions. Call this **before** setting breakpoints (Phase 2) or triggering workloads (Phase 3), so no current-session events can be lost.

```
debugger_events(operation: "fetch", types: ["debugger.breakpoint_hit"], max_events: 100)
```

Use a `types` filter to drain only stale breakpoint events while preserving diagnostic events (`debugger.error`, `debugger.vm_disconnect`) that may still be relevant.

The response always includes `latest_sequence` — the monotonic high-water mark of the event generator — regardless of how many events were matched or drained. Store this value; you will pass it as `since_sequence` in Phase 4.

**Important:** `fetch` removes matching events from the queue. At this point in the flow (before breakpoints exist), any drained breakpoint event is stale. In subsequent loop iterations (Phase 6 → Phase 4), use `latest_sequence` from the previous `wait` response instead of re-fetching.

If the queue is empty, `latest_sequence` may be 0. That's fine — pass it anyway.

### Phase 2: Set Breakpoints

Set breakpoints at your target locations. Always use fully qualified class names.

```
debugger_breakpoints(
  operation: "set",
  class_name: "com.example.service.OrderService",
  line_number: 142
)
```

If you set `condition`, treat it as metadata in current runtime behavior (not guaranteed hit filtering):
```
debugger_breakpoints(
  operation: "set",
  class_name: "com.example.service.OrderService",
  line_number: 142,
  condition: "order.getTotal() < 0",
  suspend_policy: "thread"
)
```
After a hit, gate manually with `debugger_evaluate`, then resume quickly when the gate is false.

Verify the breakpoint was set by checking the response:
- `status_detail: "created"` — new breakpoint
- `status_detail: "updated"` — existing breakpoint was modified
- `status_detail: "unchanged"` — already exists with same config

### Phase 3: Trigger Workload

**Embedded mode** — use `jshell_async`:
```
jshell_async(operation: "start", code: "new OrderService().processOrder(testOrder)")
```
Response returns `task_id` immediately. The code runs on a separate thread.

**Proxy mode** — trigger externally:
- Ask the user: "Please trigger the operation now"
- Use Bash: `curl -X POST http://localhost:8080/api/orders -d '...'`
- The user runs a test, clicks a button, or sends a request

### Phase 4: Wait for Event

Wait for a breakpoint hit using the sequence cursor from Phase 1:
```
debugger_events(
  operation: "wait",
  types: ["debugger.breakpoint_hit"],
  since_sequence: <latest_sequence>,
  timeout_ms: 30000
)
```

**Key parameters:**
- `types`: Filter for specific event types. Without this, you may get lifecycle events (`thread_start`, `thread_death`, and possibly method/exception events if separately enabled) instead.
- `since_sequence`: Only return events with sequence > this value. Prevents processing stale events.
- `timeout_ms`: How long to block waiting. Returns with `timed_out: true` if no matching event arrives.

**If the wait times out:**
1. Check `debugger_breakpoints(operation: "list")` — is the breakpoint `verified` or `pending`?
2. Check the async task status — did it complete without hitting the breakpoint?
3. If you set a breakpoint `condition`, remember it is not a guaranteed hit filter; diagnose class/line/path first
4. Retry the wait with a fresh `since_sequence`

### Phase 5: Inspection Sequence

When a breakpoint hits, the event payload includes `thread_id`. Use it for all inspection:

**Stack trace (filtered to remove framework noise):**
```
debugger_stacktrace(
  operation: "capture_filtered",
  thread_id: <tid>,
  exclude_patterns: ["java.*", "javax.*", "jdk.*", "sun.*"]
)
```

**Local variables at the current frame:**
```
debugger_variables(
  operation: "get_variables",
  thread_id: <tid>,
  frame_index: 0
)
```

**Evaluate a hypothesis:**
```
debugger_evaluate(
  operation: "evaluate",
  thread_id: <tid>,
  expression: "order.getItems().stream().mapToDouble(Item::getPrice).sum()"
)
```

**Expand a complex object (if variable_reference was returned):**
```
debugger_variables(
  operation: "get_child_variables",
  thread_id: <tid>,
  variable_reference: <ref_from_get_variables>
)
```

### Phase 6: Resume

When inspection is complete, resume all threads:
```
debugger_session(operation: "resume_all")
```

Update your sequence cursor from the event response's `latest_sequence`.

If you expect more breakpoint hits, loop back to Phase 4 with the updated cursor.

**If you need to step before resuming:**
```
debugger_step(operation: "step_over", thread_id: <tid>)
```
After stepping, re-inspect (Phase 5), then resume when done.

### Phase 7: Collect Results

**Embedded mode:**
```
jshell_async(operation: "status", task_id: "<task_id>")
```
Check for `status: "success"`, `status: "failed"`, or `status: "running"`.

**Proxy mode:**
- Check the HTTP response if you used `curl`
- Ask the user what result they observed
- Check application logs

## Cursor-Based Event Management

The `since_sequence` mechanism prevents stale event processing:

- Every event has a monotonically increasing `sequence` number
- Every `fetch`/`wait` response includes `latest_sequence` — the highest sequence number ever assigned (monotonic, does not decrease when events are consumed)
- Passing `since_sequence: N` means "only return events with sequence > N"
- **Both `fetch` and `wait` consume (remove) matching events from the queue.** The `since_sequence` cursor skips events by sequence number, but matched events are still drained.
- `clear` also removes events; it is functionally identical to `fetch` but returns a `cleared` count instead of event payloads
- In loops, prefer using `latest_sequence` from the previous `wait` response as your cursor rather than re-fetching

**Pattern:**
```
1. fetch → latest_sequence = 5
2. set breakpoints, trigger workload
3. wait(since_sequence: 5) → breakpoint_hit at sequence 8, latest_sequence = 8
4. inspect, resume
5. wait(since_sequence: 8) → next breakpoint_hit at sequence 12
```

**Never reset your cursor to 0 mid-session** unless you intentionally want to reprocess old events.

## Timeout Management

Three timeout layers interact during debugging:

### Layer 1: Debugger Events Timeout (`timeout_ms`)
The `debugger_events wait` parameter. How long the debugger waits for a matching event.
- Default: 30000 ms (30 seconds)
- Recommendation: 30000 for normal debugging, 60000+ for slow workloads

### Layer 2: MCP Adapter Request Timeout (`MCP_REQUEST_TIMEOUT`)
The TCP adapter's timeout for any MCP `tools/call` request.
- Default: 30000 ms
- **Must be greater than Layer 1** for `debugger_events wait` calls

### Layer 3: Adapter Grace Period (`MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS`)
Extra padding the adapter adds for `debugger_events wait` requests.
- Node adapter default: 5000 ms (`config/mcp/mcp-tcp-adapter.js`)
- Java adapter default: 2000 ms (`McpTcpAdapter`)
- The adapter automatically extends its own timeout to: `timeout_ms + grace`
- This prevents the adapter from timing out before the debugger returns

**How auto-extension works:** When the adapter detects a `debugger_events` call with `operation=wait`, it sets its request timeout to `max(MCP_REQUEST_TIMEOUT, timeout_ms + MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS)`.

**Recommendations:**
- Normal debugging: `timeout_ms: 30000`, verify adapter timeout is >= `30000 + grace`
- Slow workloads: `timeout_ms: 60000`, verify adapter timeout is >= `60000 + grace`
- Very slow / manual triggers: `timeout_ms: 120000`, increase adapter timeout and grace accordingly

## Error Recovery Patterns

### Session Lost (Error 1000: SESSION_NOT_ACTIVE)
The JDWP connection dropped (target JVM crashed, network interruption).
1. `debugger_session(operation: "start")` — reconnect
2. Re-set all breakpoints (they were lost with the session)
3. Re-trigger the workload
4. Establish a new baseline sequence

### Thread Not Found (Error 1200: THREAD_NOT_FOUND)
The thread exited between the event and your inspection call.
1. `debugger_threads(operation: "list", suspended_only: true)` — find the actual suspended thread
2. Use the correct `thread_id` from the refreshed list

### Event Queue Noise
Too many lifecycle events are cluttering your waits.
1. Always use `types` filter: `types: ["debugger.breakpoint_hit"]`
2. Always use `since_sequence` cursor
3. If hopelessly confused: `debugger_events(operation: "fetch", types: ["debugger.breakpoint_hit"], max_events: 100)` to drain stale breakpoint events, store new `latest_sequence`, continue with cursor. Avoid unfiltered drains — they discard `debugger.error` and `debugger.vm_disconnect` events you may need for diagnosis.

### Task Completed Before Breakpoint
The async task finished but no breakpoint was hit.
1. Check `debugger_breakpoints(operation: "list")` — is the breakpoint `pending` (class not loaded) or `verified`?
2. If `pending`: the class was never loaded. Use `defer_if_unloaded: true` and re-trigger.
3. If `verified` and still no hits: the code path likely was not executed, or class/line selection is wrong
4. If a `condition` field is set, do not treat it as the reason for missing hits in current runtime behavior
