# Descartes MCP Tools Reference

Comprehensive reference for all tools provided by Descartes MCP.

## Tool Conventions

- All tools now return structured JSON payloads (`type: "json"`), so adapters can consume responses directly without reparsing text.
- Error codes follow three buckets: `1000-1999` (validation/parameter issues), `2000-2999` (preconditions or unavailable resources), and `3000+` (execution/runtime failures). Debugger-specific codes remain under their dedicated ranges.
- Schemas encode per-operation requirements. Calls that omit required arguments (e.g., `thread_inspect` without `thread_ids`/`thread_names`, `debugger_watch` evaluate without a thread identifier) will return validation errors instead of attempting implicit fallbacks.
- Precondition-sensitive tools (debugger stepping/evaluation, hot reload, JShell) surface prerequisites explicitly: ensure sessions are active, threads are suspended, and agents are attached before invoking operations.

## JShell Async

**Tool**: `jshell_async`

**Purpose**: Execute JShell snippets asynchronously so clients can trigger workloads, wait for debugger events, and later collect evaluation output.

### Operations

#### 1. start

Submit code for asynchronous execution. Returns immediately with a task identifier.

**Parameters:**
- `code` (string, required): JShell snippet to evaluate
- `session_id` (string, optional): Existing session to reuse; auto-generated if omitted
- `timeout_seconds` (integer, optional): Cancel the evaluation if it exceeds the timeout
- `close_session` (boolean, optional): Close the JShell session after completion (default: false)
- `extend_expiry_minutes` (integer, optional): Extend session idle timeout on success

**Returns:**
```json
{
  "task_id": "a2a9f22c-a5f8-4a3b-8a7d-3b2c3f5d0d3f",
  "session_id": "f61f6f09-7555-47fd-82f9-6ed0b76ad7a6",
  "status": "queued",
  "created_at": "2024-07-27T19:03:42.154Z",
  "timeout_seconds": 30,
  "message": "JShell async task started"
}
```

#### 2. status

Poll task progress or retrieve the final result.

**Parameters:**
- `task_id` (string, required): Identifier returned from `start`
- `include_result` (boolean, optional): Include the captured result payload if completed (default: true)

**Returns (running):**
```json
{
  "task_id": "a2a9f22c-a5f8-4a3b-8a7d-3b2c3f5d0d3f",
  "session_id": "f61f6f09-7555-47fd-82f9-6ed0b76ad7a6",
  "status": "running",
  "created_at": "2024-07-27T19:03:42.154Z",
  "started_at": "2024-07-27T19:03:42.160Z"
}
```

**Returns (completed):**
```json
{
  "task_id": "a2a9f22c-a5f8-4a3b-8a7d-3b2c3f5d0d3f",
  "session_id": "f61f6f09-7555-47fd-82f9-6ed0b76ad7a6",
  "status": "success",
  "created_at": "2024-07-27T19:03:42.154Z",
  "started_at": "2024-07-27T19:03:42.160Z",
  "completed_at": "2024-07-27T19:03:42.228Z",
  "result": {
    "out": "42\n",
    "events": [
      {
        "source": "int answer = 6 * 7;",
        "status": "VALID"
      }
    ],
    "sessionId": "f61f6f09-7555-47fd-82f9-6ed0b76ad7a6",
    "startedAt": "2024-07-27T19:03:42.160Z",
    "finishedAt": "2024-07-27T19:03:42.227Z"
  }
}
```

Errors (timeout, cancellation, failure) return the same structure with `status` set to `timeout`, `cancelled`, or `failed`, and include an `error` object with details.

#### 3. cancel

Abort a queued or running task.

**Parameters:**
- `task_id` (string, required): Identifier returned from `start`

**Returns:**
```json
{
  "task_id": "a2a9f22c-a5f8-4a3b-8a7d-3b2c3f5d0d3f",
  "session_id": "f61f6f09-7555-47fd-82f9-6ed0b76ad7a6",
  "status": "cancelled",
  "created_at": "2024-07-27T19:03:42.154Z",
  "completed_at": "2024-07-27T19:03:45.003Z",
  "error": {
    "type": "Cancelled",
    "message": "JShell async task cancellation requested"
  },
  "message": "JShell async task cancellation requested"
}
```

Successful cancellation is best-effort: JShell attempts to stop active evaluations and resume the session so other commands can run.

## Debugger Events

**Tool**: `debugger_events`

**Purpose**: Poll buffered debugger notifications so MCP clients can block for breakpoints or drain queued events without transport-level callbacks.

### Operations

#### 1. wait

Block until an event matching optional filters arrives, or the timeout expires.

**Parameters:**
- `timeout_ms` (integer, optional): Maximum time to wait (default: 30000)
- `types` (array, optional): Event types to match (e.g. `["debugger.breakpoint_hit"]`)
- `thread_id` (integer, optional): Filter by payload `thread_id`

**Returns (event):**
```json
{
  "timed_out": false,
  "timeout_ms": 10000,
  "event": {
    "sequence": 12,
    "type": "debugger.breakpoint_hit",
    "timestamp": "2024-07-27T19:05:14.201Z",
    "payload": {
      "thread_id": 42,
      "thread_name": "main",
      "class": "com.example.Service",
      "line": 123
    }
  },
  "pending_events": 0
}
```

**Returns (timeout):**
```json
{
  "timed_out": true,
  "timeout_ms": 1000,
  "pending_events": 0
}
```

#### 2. fetch

Drain queued events immediately.

**Parameters:**
- `max_events` (integer, optional): Maximum number of events to return (default: 10, max: 100)
- `types`, `thread_id`: Optional filters identical to `wait`

**Returns:**
```json
{
  "count": 2,
  "events": [
    { "sequence": 15, "type": "debugger.step_complete", "timestamp": "...", "payload": { ... } },
    { "sequence": 16, "type": "debugger.breakpoint_hit", "timestamp": "...", "payload": { ... } }
  ],
  "pending_events": 0
}
```

#### 3. clear

Remove all buffered events and report how many were discarded.

**Returns:**
```json
{
  "cleared": 25,
  "pending_events": 0
}
```

## Thread Analyzer

**Tool**: `thread_analyzer`

**Purpose**: Progressive disclosure thread analysis for debugging concurrency issues, deadlocks, and performance problems.

### Design Philosophy

The thread analyzer follows a **progressive disclosure pattern** to avoid overwhelming responses:
1. **List** - Get lightweight summaries of all threads
2. **Filter** - Narrow down to threads of interest
3. **Inspect** - Deep dive into specific threads with full stack traces

This prevents the massive response sizes (200KB-5MB) that occur when requesting stack traces for all threads at once.

### Operations

#### 1. thread_list

Get a lightweight summary of threads with filtering and sorting.

**Parameters:**
- `state_filter` (array, optional): Filter by thread states
  - Values: `RUNNABLE`, `BLOCKED`, `WAITING`, `TIMED_WAITING`, `NEW`, `TERMINATED`
  - Example: `["RUNNABLE", "BLOCKED"]`
- `name_pattern` (string, optional): Regex pattern to filter thread names
  - Example: `"pool-.*"` matches all pool threads
- `min_cpu_time_ms` (integer, optional): Minimum CPU time in milliseconds
- `sort_by` (string, optional): Sort field
  - Values: `cpu_time` (default), `name`, `id`, `state`
- `descending` (boolean, optional): Sort order (default: true)
- `max_results` (integer, optional): Maximum threads to return (default: 50)

**Returns:**
```json
{
  "status": "success",
  "total_threads": 120,
  "matched_threads": 45,
  "returned_threads": 45,
  "threads": [
    {
      "id": 42,
      "name": "pool-worker-1",
      "state": "RUNNABLE",
      "priority": 5,
      "daemon": false,
      "cpu_time_ms": 1234,
      "user_time_ms": 1100
    }
    // ... more threads (no stack traces)
  ]
}
```

**Response Size**: ~5-10KB for 50 threads

**Example:**
```json
{
  "operation": "thread_list",
  "state_filter": ["BLOCKED", "WAITING"],
  "min_cpu_time_ms": 100,
  "sort_by": "cpu_time",
  "max_results": 20
}
```

#### 2. thread_inspect

Get detailed information about specific threads including stack traces.

**Parameters:**
- `thread_ids` (array, required*): Thread IDs to inspect
  - Example: `[42, 57, 103]`
- `thread_names` (array, required*): Thread names to inspect (alternative to thread_ids)
  - Example: `["main", "pool-worker-1"]`
- `include_stack` (boolean, optional): Include stack traces (default: true)
- `max_stack_depth` (integer, optional): Maximum stack trace depth (default: 20)
- `filter_stack_pattern` (string, optional): Regex to filter stack frames
  - Only frames matching the pattern are included
  - Example: `"com\\.myapp\\..*"` shows only application frames
- `include_locks` (boolean, optional): Include lock information (default: true)
- `include_monitors` (boolean, optional): Include monitor details (default: true)
- `include_synchronizers` (boolean, optional): Include synchronizer details (default: true)

*One of `thread_ids` or `thread_names` is required.

**Returns:**
```json
{
  "status": "success",
  "requested_threads": 3,
  "found_threads": 3,
  "threads": [
    {
      "id": 42,
      "name": "pool-worker-1",
      "state": "WAITING",
      "priority": 5,
      "daemon": false,
      "cpu_time_ms": 1234,
      "user_time_ms": 1100,
      "blocked_count": 0,
      "blocked_time_ms": 0,
      "waited_count": 45,
      "waited_time_ms": 12345,
      "lock_name": "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject@4a574795",
      "lock_owner_id": -1,
      "stack_trace": [
        "jdk.internal.misc.Unsafe.park(Native Method)",
        "java.util.concurrent.locks.LockSupport.park(LockSupport.java:341)",
        "com.myapp.Worker.processTask(Worker.java:123)"
      ],
      "locks_held": [],
      "monitors": []
    }
  ],
  "truncated": false,
  "approximate_size_bytes": 15234
}
```

**Response Size**: ~10-50KB depending on stack depth and number of threads

**Response Size Limit**: Responses are capped at 200KB. If exceeded, thread details are truncated and `truncated: true` is returned.

**Example:**
```json
{
  "operation": "thread_inspect",
  "thread_ids": [42, 57, 103],
  "include_stack": true,
  "max_stack_depth": 15,
  "filter_stack_pattern": "com\\.bitsapplied\\..*"
}
```

#### 3. thread_search

Convenience operation that combines thread_list filtering with thread_inspect. Finds threads matching criteria and returns detailed info.

**Parameters:**
Combines all parameters from `thread_list` and `thread_inspect`:
- `state_filter`, `name_pattern`, `min_cpu_time_ms`, `sort_by`, `max_results` (from thread_list)
- `include_stack`, `max_stack_depth`, `filter_stack_pattern`, etc. (from thread_inspect)

**Returns:**
Same format as `thread_inspect` but with threads filtered by search criteria.

**Example:**
```json
{
  "operation": "thread_search",
  "name_pattern": "pool-.*",
  "state_filter": ["WAITING"],
  "include_stack": true,
  "max_stack_depth": 10
}
```

#### 4. deadlocks

Detect circular thread dependencies (deadlocks).

**Parameters:**
- `include_stack` (boolean, optional): Include stack traces (default: true)
- `max_stack_depth` (integer, optional): Maximum stack trace depth (default: 20)

**Returns (no deadlocks):**
```json
{
  "status": "success",
  "deadlocks_found": false,
  "message": "No deadlocks detected"
}
```

**Returns (deadlocks found):**
```json
{
  "status": "success",
  "deadlocks_found": true,
  "deadlock_count": 2,
  "threads": [
    {
      "id": 42,
      "name": "Thread-A",
      "state": "BLOCKED",
      "lock_name": "java.lang.Object@12345",
      "lock_owner_id": 57,
      "lock_owner_name": "Thread-B",
      "stack_trace": [...]
    },
    {
      "id": 57,
      "name": "Thread-B",
      "state": "BLOCKED",
      "lock_name": "java.lang.Object@67890",
      "lock_owner_id": 42,
      "lock_owner_name": "Thread-A",
      "stack_trace": [...]
    }
  ]
}
```

**Example:**
```json
{
  "operation": "deadlocks",
  "include_stack": true,
  "max_stack_depth": 30
}
```

#### 5. thread_dump

Generate a full thread dump in text format (similar to jstack output) with optional filtering to reduce size.

**Parameters:**
- `max_stack_depth` (integer, optional): Maximum stack trace depth (default: 50)
- `name_pattern` (string, optional): Regex pattern to filter threads by name
  - Example: `"myapp-.*"` includes only threads starting with "myapp-"
- `state_filter` (array, optional): Filter by thread states
  - Values: `RUNNABLE`, `BLOCKED`, `WAITING`, `TIMED_WAITING`, `NEW`, `TERMINATED`
  - Example: `["RUNNABLE", "BLOCKED"]`
- `filter_stack_pattern` (string, optional): Regex to filter stack frames
  - Only frames matching the pattern are included
  - Example: `"com\\.myapp\\..*"` shows only application frames
  - Example: `"^(?!java\\.|jdk\\.).*"` excludes JDK frames

**Returns:**
```json
{
  "status": "success",
  "total_threads": 150,
  "filtered_threads": 12,
  "thread_dump": "Full thread dump text...",
  "timestamp": 1698765432000
}
```

**Example (unfiltered):**
```json
{
  "operation": "thread_dump",
  "max_stack_depth": 50
}
```

**Example (filtered to application threads only):**
```json
{
  "operation": "thread_dump",
  "name_pattern": "myapp-.*",
  "filter_stack_pattern": "com\\.myapp\\..*",
  "max_stack_depth": 30
}
```

**Example (only blocked threads):**
```json
{
  "operation": "thread_dump",
  "state_filter": ["BLOCKED"],
  "max_stack_depth": 50
}
```

**Filtering Benefits:**
- **Thread filtering** (`name_pattern`, `state_filter`): Reduces thread dump from 150+ threads to only those you care about
- **Stack filtering** (`filter_stack_pattern`): Focuses on application frames, hiding JDK/library internals
- **Combined filtering**: Can reduce response from 500KB+ to under 50KB

### Typical Workflows

#### Workflow 1: Find Blocked Threads

```javascript
// Step 1: Get all blocked threads
{
  "operation": "thread_list",
  "state_filter": ["BLOCKED"],
  "sort_by": "cpu_time"
}

// Step 2: Inspect the top blocked thread
{
  "operation": "thread_inspect",
  "thread_ids": [42],
  "include_stack": true,
  "max_stack_depth": 20
}
```

#### Workflow 2: Debug High CPU Usage

```javascript
// Step 1: Find threads consuming CPU
{
  "operation": "thread_list",
  "state_filter": ["RUNNABLE"],
  "min_cpu_time_ms": 1000,
  "sort_by": "cpu_time",
  "max_results": 10
}

// Step 2: Examine what they're doing
{
  "operation": "thread_inspect",
  "thread_ids": [42, 57, 103],
  "include_stack": true,
  "max_stack_depth": 30
}
```

#### Workflow 3: Investigate Application Hang

```javascript
// Step 1: Check for deadlocks
{
  "operation": "deadlocks"
}

// Step 2: If no deadlocks, find waiting threads
{
  "operation": "thread_search",
  "name_pattern": "myapp-.*",
  "state_filter": ["WAITING", "TIMED_WAITING"],
  "include_stack": true,
  "filter_stack_pattern": "com\\.myapp\\..*"
}
```

### Performance Characteristics

| Operation | Typical Time | Response Size | Use Case |
|-----------|-------------|---------------|----------|
| thread_list | 10-50ms | 5-10KB | Initial exploration |
| thread_inspect (3 threads) | 20-100ms | 10-30KB | Targeted investigation |
| thread_search | 30-150ms | 10-50KB | Combined filter+inspect |
| deadlocks | 50-200ms | 5-50KB | Deadlock detection |
| thread_dump | 100-500ms | 50-500KB | Full system snapshot |

### Response Size Management

The thread_analyzer implements **multiple protections** to prevent responses that are too large:

#### Built-in Limits

1. **Max threads per inspect**: 50 threads maximum for `thread_inspect` operation
   - If you need more, use `thread_search` with filtering or make multiple calls

2. **Response size limit**: 200KB maximum for operations with detailed thread info
   - Applies to: `thread_inspect` and `thread_search` with `include_details=true`
   - Responses are truncated when limit reached
   - `truncated: true` field indicates when truncation occurred

3. **Size warnings**: `thread_dump` warns when dumping >100 threads without filtering

#### What Happens When Limits Are Hit

**thread_inspect with too many threads:**
```json
{
  "error": "Too many threads requested: 75 (max 50).
   To inspect more threads, use thread_search with include_details=true..."
}
```

**Response size limit reached:**
```json
{
  "status": "success",
  "truncated": true,
  "truncation_reason": "Response size limit reached (200000 bytes)",
  "suggestion": "Try: 1) Reducing max_stack_depth, 2) Using filter_stack_pattern...",
  "returned_threads": 12,
  "requested_threads": 20
}
```

**Large thread_dump warning:**
```json
{
  "status": "success",
  "size_warning": "Warning: Dumping 150 threads without filtering may produce very large output (500KB+)...",
  "total_threads": 150,
  "filtered_threads": 150
}
```

#### Safe Usage Guidelines

**DO:**
- ✅ Start with `thread_list` to get overview (5-10KB)
- ✅ Use `thread_inspect` for specific thread IDs (10-50KB)
- ✅ Filter `thread_dump` with `name_pattern` or `state_filter`
- ✅ Use `filter_stack_pattern` to show only application frames
- ✅ Set reasonable `max_stack_depth` (10-30 frames usually sufficient)

**DON'T:**
- ❌ Request stack traces for all threads at once
- ❌ Use `thread_dump` without filtering on systems with >100 threads
- ❌ Set `max_stack_depth` > 100 (rarely useful)
- ❌ Inspect >50 threads with `thread_inspect` (use `thread_search` instead)

#### Response Size Examples

| Operation | Config | Typical Size | Notes |
|-----------|--------|--------------|-------|
| thread_list | 50 threads, no stacks | 5-10KB | Always safe |
| thread_inspect | 5 threads, depth=20 | 15-30KB | Safe |
| thread_inspect | 50 threads, depth=50 | 150-200KB | At limit |
| thread_search | include_details=false | 5-20KB | Safe |
| thread_search | include_details=true, 20 results | 50-100KB | Usually safe |
| thread_dump | 50 threads, no filter | 100-150KB | Acceptable |
| thread_dump | 200 threads, no filter | 500KB-2MB | ⚠️ Too large! |
| thread_dump | 200 threads, filtered | 50-200KB | Safe with filtering |

### Migration from Old Operations

The thread_analyzer was redesigned in v0.0.1-SNAPSHOT to address massive response sizes. Old operations have been removed:

| Old Operation | New Equivalent |
|--------------|----------------|
| `threads` | `thread_list` (summary) + `thread_inspect` (details) |
| `locks` | `thread_list` with `state_filter: ["BLOCKED"]` |
| `waiting` | `thread_list` with `state_filter: ["WAITING"]` |
| `blocked` | `thread_list` with `state_filter: ["BLOCKED"]` |

**Key Change**: Stack traces are now opt-in via `thread_inspect` rather than opt-out, preventing accidental 5MB responses.
