# Troubleshooting

## Error Code Reference

### Session Errors (1000-1099)

| Code | Name | Message | Recovery |
|------|------|---------|----------|
| 1000 | `SESSION_NOT_ACTIVE` | No active debug session | Run `debugger_session(operation: "start")`. Verify JDWP host/port. |
| 1001 | `SESSION_ALREADY_ACTIVE` | Debug session already active | Use the existing session, or `stop` then `start` to reset. |
| 1002 | `SESSION_START_FAILED` | Failed to start debug session | Check JDWP flags on target JVM. Verify port is not in use. |
| 1003 | `JDWP_CONNECTION_FAILED` | Failed to connect to JDWP | Target JVM not running or JDWP not enabled. Check host/port. Increase `jdwp_timeout`. |
| 1004 | `SESSION_DISCONNECT_FAILED` | Failed to disconnect debug session | Force `stop`. The session may be in a bad state. |
| 1005 | `SESSION_INVALID_STATE` | Invalid session state for this operation | Check `status` first. The session may be in a transitional state. Wait and retry. |

### Breakpoint Errors (1100-1199)

| Code | Name | Message | Recovery |
|------|------|---------|----------|
| 1100 | `BREAKPOINT_SET_FAILED` | Failed to set breakpoint | Check class name spelling. Verify session is active. |
| 1101 | `BREAKPOINT_REMOVE_FAILED` | Failed to remove breakpoint | Verify `breakpoint_id` exists. Check `list`. |
| 1102 | `BREAKPOINT_NOT_FOUND` | Breakpoint not found | The breakpoint was already removed or never created. Check `list`. |
| 1103 | `BREAKPOINT_INVALID_LOCATION` | Invalid breakpoint location | Line is outside any method or class. Read source code to verify. |
| 1104 | `BREAKPOINT_CLASS_NOT_FOUND` | Class not found for breakpoint | Class not loaded (use `defer_if_unloaded: true`) or class name is wrong. |
| 1105 | `BREAKPOINT_LINE_NOT_EXECUTABLE` | Line is not executable | Line is a comment, blank line, or declaration. Use `resolve_line` to find nearest executable line. |
| 1106 | `BREAKPOINT_ALREADY_EXISTS` | Breakpoint already exists at this location | Use `upsert` to update, or `list` to find the existing breakpoint ID. |

### Thread Errors (1200-1299)

| Code | Name | Message | Recovery |
|------|------|---------|----------|
| 1200 | `THREAD_NOT_FOUND` | Thread not found | Thread may have exited. Refresh with `debugger_threads(operation: "list")`. |
| 1201 | `THREAD_NOT_SUSPENDED` | Thread is not suspended | Suspend the thread first, or wait for a breakpoint to suspend it. |
| 1202 | `THREAD_ALREADY_SUSPENDED` | Thread is already suspended | Thread is already paused. Proceed with inspection or resume. |
| 1203 | `THREAD_RESUME_FAILED` | Failed to resume thread | Thread may have exited. Check thread list. Try `resume_all`. |
| 1204 | `THREAD_SUSPEND_FAILED` | Failed to suspend thread | Thread may have exited. Refresh thread list. |

### Variable Errors (1300-1399)

| Code | Name | Message | Recovery |
|------|------|---------|----------|
| 1300 | `VARIABLE_NOT_FOUND` | Variable not found | Variable may not be in scope at this frame. Check `frame_index`. |
| 1301 | `VARIABLE_INVALID_REFERENCE` | Invalid variable reference | Reference expired (thread was resumed). Re-suspend and get fresh references. |
| 1302 | `VARIABLE_SET_FAILED` | Failed to set variable value | Value type may not match. Check variable type first. |
| 1303 | `VARIABLE_FETCH_FAILED` | Failed to fetch variable value | Thread may have resumed. Re-inspect after confirming suspension. |

### Expression Evaluation Errors (1400-1499)

| Code | Name | Message | Recovery |
|------|------|---------|----------|
| 1400 | `EVALUATION_FAILED` | Expression evaluation failed | Check expression syntax. Ensure variables are in scope at the current frame. |
| 1401 | `EVALUATION_TIMEOUT` | Expression evaluation timeout | Expression is too complex or causes deadlock. Simplify. |
| 1402 | `EVALUATION_COMPILATION_FAILED` | Failed to compile expression | Syntax error or unknown type. Use fully qualified class names. Check error details for `unresolved_identifiers`. |
| 1403 | `EVALUATION_EXECUTION_FAILED` | Failed to execute expression | Runtime error in expression (NPE, ArrayIndexOutOfBounds, etc.). |
| 1404 | `EVALUATION_TYPE_MISMATCH` | Type mismatch in expression | Expression returns wrong type. Check operand types. |

### Generic Errors (9994-9999)

| Code | Name | Message | Recovery |
|------|------|---------|----------|
| 9994 | `OPERATION_TIMEOUT` | Debugger operation timed out | Increase `timeout_ms` or `jdwp_timeout`. Target may be slow or unresponsive. |
| 9995 | `INTERNAL_ERROR` | Internal error | Unexpected server error. Check server logs. Restart session. |
| 9996 | `INVALID_FRAME` | Invalid stack frame | `frame_index` out of range. Check stack depth with `capture`. |
| 9997 | `INVALID_PARAMETERS` | Invalid parameters | Check required parameters for the operation. |
| 9998 | `INVALID_OPERATION` | Invalid operation | Operation name is wrong. Check tool reference for valid operations. |
| 9999 | `UNKNOWN_ERROR` | Unknown error occurred | Unexpected error. Check server logs. Try `stop` then `start`. |

## Common Failure Scenarios

### "Breakpoint never hits"

**Diagnostic procedure:**
1. `debugger_breakpoints(operation: "list")` — check the breakpoint status
2. If `pending`: Class not loaded yet. Either the trigger doesn't load it, or the class name is wrong.
3. If `verified`: The code path isn't being executed. Verify the trigger exercises the right path.
4. If a `condition` field is present, do not assume it filtered the hit in current runtime behavior
5. If using `since_sequence`: verify you're not accidentally skipping the event by using a cursor that's ahead of it

### "`debugger_events wait` always times out"

**Diagnostic procedure:**
1. Is the breakpoint set and `verified`? Check `debugger_breakpoints(operation: "list")`
2. Was the workload triggered? Check `jshell_async(operation: "status", task_id: "...")` or ask the user
3. Is `since_sequence` correct? If it's set too high, events are being skipped. Try `fetch` without `since_sequence` to see what's in the queue.
4. Is the `timeout_ms` long enough? The workload may take longer than 30 seconds. Try 60000.
5. Is class/line/path selection correct? In current runtime behavior, `condition` is not a reliable explanation for missing hits.

### "Variable reference expired"

**Cause:** The thread was resumed between `get_variables` and `get_child_variables`. Variable references are only valid while the thread is suspended.

**Fix:**
1. Re-suspend the thread or wait for the next breakpoint hit
2. Call `get_variables` again to get fresh references
3. Expand within the same suspension window — don't resume between `get_variables` and `get_child_variables`

### "Adapter timeout"

**Symptom:** `Request timeout after ...` or `Adapter timeout after ... while waiting for debugger_events.wait`

**Cause:** The resolved tool timeout, adapter wait grace, or MCP client deadline is too short for the requested wait.

**Fix:**
1. The adapter auto-extends for `debugger_events wait` using `MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS` (Node default 5000 ms, Java adapter default 2000 ms)
2. If still timing out, increase the call's `timeout_ms` or the adapter default `MCP_TOOL_TIMEOUT_MS` (default 60000)
3. If using very long waits (>60s), keep `MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS` large enough for transport overhead
4. Increase the MCP client deadline too (Codex: `tool_timeout_sec`; Claude Code or Gemini CLI: MCP server `timeout`); prefer the checked-in project client config
5. For non-wait tool operations, the operation itself is slow. Increase its supported timeout argument or `MCP_TOOL_TIMEOUT_MS`.

### "Cannot find symbol in evaluation"

**Symptom:** `EVALUATION_COMPILATION_FAILED` with unresolved identifiers

**Diagnostic procedure:**
1. Check `frame_index` — you may be evaluating in the wrong frame
2. Use `get_variables` to see what's in scope at the current frame
3. Use fully qualified class names for types not in scope: `java.util.List` instead of `List`
4. For `this` fields, use `this.fieldName` explicitly
5. Check the error response's `unresolved_identifiers` field for specifics

## Full Reset Procedure

When debugging is hopelessly confused, reset everything:

```
1. debugger_session(operation: "resume_all")       — unfreeze all threads
2. debugger_breakpoints(operation: "remove_all")   — remove all breakpoints
3. debugger_watch(operation: "remove_all")          — remove all watches
4. debugger_session(operation: "stop")              — disconnect from JDWP
5. debugger_session(operation: "start")             — reconnect fresh
```

After a full reset:
- All breakpoints are gone — you need to re-set them
- All watches are gone — you need to re-add them
- The event queue is fresh — no stale events
- Establish a new baseline `latest_sequence` with `fetch`

## Partial Recovery (Event Queue Confusion)

If the event queue has stale events but the session is otherwise fine:

```
1. debugger_events(operation: "fetch", types: ["debugger.breakpoint_hit"], max_events: 100)   — drain stale breakpoint events
2. Store the latest_sequence from the response
3. Continue with since_sequence: <new latest_sequence>
```

This drains stale breakpoint events and gives you a fresh cursor. The `latest_sequence` value is the monotonic high-water mark — it does not decrease when events are consumed, so your cursor remains valid for subsequent `wait` calls.
