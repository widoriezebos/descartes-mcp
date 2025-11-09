# Tool Reference

This document summarises every MCP tool that ships with Descartes. Use it alongside the dedicated guides:

- [Runtime Debugger](debugger.md) — deep dive into JShell, sessions, inspection, and diagnostics.
- [Profiler](profiler.md) — workflows for the JFR-backed profiling tools.
- [Hot Reload](hot-reload.md) — agent requirements and reload lifecycle.

## Quick Index

| Category | Tool Name | Class | Description |
|----------|-----------|-------|-------------|
| JShell | `jshell_repl` | `JShellTool` | Evaluate Java code inside the host JVM. |
| JShell | `jshell_session_manager` | `JShellSessionTool` | Manage JShell sessions (close, extend, query limits). |
| Inspection | `object_inspector` | `ObjectInspectorTool` | Reflective inspection of context-backed objects. |
| Diagnostics | `process_inspector` | `ProcessInspectorTool` | Capture stack traces, filter by thread/package. |
| Diagnostics | `system_monitoring` | `SystemMonitoringTool` | System metrics, GC, CPU usage snapshot. |
| Diagnostics | `thread_analyzer` | `ThreadAnalyzerTool` | Progressive listing/search/inspection of threads. |
| Diagnostics | `memory_analyzer` | `MemoryAnalyzerTool` | Heap pools, GC stats, pressure indicators. |
| Diagnostics | `log_file_discovery` | `LogFileDiscoveryTool` | Discover log files from Log4j2 configuration. |
| Diagnostics | `log_file_search` | `LogFileSearchTool` | Comprehensive log analysis: search, count, extract, timeline, multi-file operations. |
| Hot reload | `hot_reload_classes` | `HotClassReloadTool` | Validate and reload bytecode (requires agent). |
| Profiler | `profiler_start` | `ProfilerStartTool` | Start a JFR recording. |
| Profiler | `profiler_stop` | `ProfilerStopTool` | Stop an active recording. |
| Profiler | `profiler_hotspots` | `ProfilerHotspotsTool` | Rank CPU/allocation/lock hotspots. |
| Profiler | `profiler_call_tree` | `ProfilerCallTreeTool` | Explore aggregated call trees. |
| Profiler | `profiler_list` | `ProfilerListTool` | List stored or active profiles. |
| Profiler | `profiler_export` | `ProfilerExportTool` | Export JSON, text, or interactive flame graphs. |

## Argument Highlights

### JShell (`jshell_repl`)
- `code` — Java snippet to run (required).
- `session_id` — Provide to reuse state; omit for a fresh session.
- `reset` — Wipes session before execution.
- `close_session` — Ends session afterwards.
- `extend_expiry_minutes` — Keeps the session alive longer than the default timeout.

### JShell Session Manager (`jshell_session_manager`)
- `action` — One of `close`, `extend_expiry`, `session_count`, `get_max_sessions`, `set_max_sessions`.
- `session_id` — Required for `close`/`extend_expiry`.
- `expiry_minutes` — Optional new timeout.
- `max_sessions` — New cap when using `set_max_sessions`.

### Object Inspector (`object_inspector`)
- `expression` — Must start with the configured context alias (`context` by default).
- `operation` — `inspect`, `fields`, `methods`, `type`, or `value`.
- `include_private` — Include private members.
- `max_depth` — Depth for recursive inspection of nested objects.

### Process Inspector (`process_inspector`)
- `operation` — `snapshot`, `stacks`, or `summary`.
- `include_thread_details` / `include_stack_traces` — Toggle verbosity.
- `package_filter` — Limit stacks to matching prefixes.
- `max_stack_depth` — Cap stack trace depth.

### Thread Analyzer (`thread_analyzer`)
- `operation` — `thread_list`, `thread_inspect`, `thread_search`, `deadlocks`, or `thread_dump`.
- `state_filter` — Filter states (`RUNNABLE`, `BLOCKED`, etc.).
- `name_pattern` — Regex for thread names.
- `thread_ids` / `thread_names` — Targets for inspection.
- `max_results`, `max_stack_depth`, `filter_stack_pattern`, `include_locks`, etc. manage payload size.

### System Monitoring (`system_monitoring`)
- `include_cpu`, `include_memory`, `include_gc`, `include_threads` — Booleans to tailor the report.
- `window_seconds` — Sample window for CPU (default 15).

### Memory Analyzer (`memory_analyzer`)
- `include_usage_by_pool` — Break down heap pools.
- `include_histogram` — Estimate allocation rates.
- `include_gc_details` — Include collector metrics.

### Log File Discovery (`log_file_discovery`)
- `operation` — `list` (all log files), `appenders` (appender configs), or `discover` (rolled files for a pattern).
- `file_pattern` — Log4j2 file pattern for discovering rolled files (required for `discover` operation).
- Automatically discovers log files from Log4j2 runtime configuration.
- Extracts timestamp patterns from Log4j2 layouts for guaranteed parsing.
- Returns file paths, sizes, timestamps, rolled file lists, and timestamp patterns.

### Log File Search (`log_file_search`)

**Agent-friendly comprehensive log analysis with bash-parity for remote scenarios.**

**Operations:**
- `search`/`grep` — Pattern matching with optional context lines
- `count` — Count matches without returning content (bandwidth optimization)
- `tail` — Last N lines
- `head` — First N lines
- `range` — Extract specific line number range
- `time_range` — Filter by timestamp range
- `extract` — Extract captured groups from regex patterns
- `between` — Extract content between start/end markers
- `timeline` — Time-series frequency analysis of matches

**File Specification** (at least one required, or defaults to all .log files):
- `file_path` — Single file path
- `file_paths` — Array of file paths for explicit multi-file operations
- `file_pattern` — Glob pattern (e.g., `**/*.log`, `app-*.log`)
- If omitted: defaults to `**/*.log` (searches all discovered log files)

**Pattern Matching:**
- `pattern` — Single regex pattern
- `patterns` — Array of patterns for multi-pattern search
- `pattern_mode` — `"any"` (OR, default) or `"all"` (AND) for multiple patterns
- `case_insensitive` — Case-insensitive matching (default: false)
- `invert_match` — Exclude matching lines, like `grep -v` (default: false)

**Context & Limits:**
- `show_context` — Smart context: auto-add 3 lines before/after (default: false)
- `context_before` / `context_after` — Manual context lines (default: 0)
- `max_results` — Per-file result limit (default: 1000)

**Extract Operation:**
- `capture_group` — Regex group number to extract (0=full match, 1=first group, etc., default: 1)
- `unique` — Deduplicate extracted values (default: false)

**Between Operation:**
- `start_marker` — Start boundary regex
- `end_marker` — End boundary regex
- `include_markers` — Include boundary lines in results (default: false)
- `max_sections` — Limit number of sections extracted

**Timeline Operation:**
- `bucket_size` — Time bucket size: `"5m"`, `"1h"`, `"30s"`, `"1d"`, etc.

**Line Operations:**
- `lines` — Number of lines for tail/head
- `start_line` / `end_line` — Line range (1-indexed, inclusive)

**Time Operations:**
- `start_time` / `end_time` — ISO 8601 timestamps
- `since` — Relative time: `"1h"`, `"30m"`, `"2d"` (alternative to start_time)
- `level_filter` — Filter by log level: ERROR, WARN, INFO, DEBUG, TRACE

**Features:**
- Multi-file operations by default (no manual loops required)
- Guaranteed timestamp parsing from Log4j2 patterns when available
- Bandwidth optimization: count returns integer, not 1000 lines
- Smart defaults: 2-3 parameters for most operations

**Examples:**
```json
{"operation": "search", "pattern": "ERROR"}                              // All logs, pattern search
{"operation": "count", "pattern": "Exception", "file_path": "app.log"}   // Just count
{"operation": "extract", "pattern": "user=([\\w]+)", "unique": true}     // Extract usernames
{"operation": "between", "start_marker": "BEGIN", "end_marker": "END"}   // Between markers
{"operation": "timeline", "pattern": "ERROR", "bucket_size": "5m"}       // 5-min buckets
```

### Hot Reload (`hot_reload_classes`)
- `packageFilter` — Glob/glob-star syntax (e.g., `com.example.*`).
- `force` — Reload even if no diff detected.
- `validateOnly` — Perform safety checks without redefining classes.
- Response reports `classesAnalyzed`, `classesReloaded`, `skipped`, and `errors` with reasons.

### Profiler Tools
See the [Profiler guide](profiler.md) for exhaustive coverage. Key arguments:
- `duration_seconds`, `profile_type`, `package_filter` (start).
- `profile_id` for every follow-up action.
- `hotspot_type`, `top_n`, `min_percentage` for hotspot queries.
- `method_pattern`, `max_depth` for call tree navigation.
- `format` (`json`, `text`, `flamegraph`) for exports.

## Response Formats

- Every tool returns a JSON string. Tools that produce large payloads (thread analyzer, profiler exports) include truncation flags or filesystem paths so clients can manage bandwidth.
- Errors follow a consistent structure: `{"success": false, "error": "...", "suggestion": "...optional..."}`.

## Extending the Catalogue

Custom tools must implement `MCPTool` and be registered with the `MCPServer` instance:

```java
server.registerTool(new MyCustomTool(context));
```

Provide an `inputSchema` that mirrors your argument structure; Descartes reuses it in `tools/list` responses so clients can self-document usage.
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
