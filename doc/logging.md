# Log Analysis

Descartes provides comprehensive log analysis capabilities designed for remote debugging scenarios where bash tools cannot access application logs directly. The logging subsystem achieves 85-90% feature parity with bash for remote log access.

## Architecture

The logging subsystem consists of three main components:

1. **Log File Discovery** (`LogFileDiscoveryTool`) — Automatically discovers log files from Log4j2 runtime configuration
2. **Log File Search** (`LogFileSearchTool`) — Comprehensive search, analysis, and extraction operations
3. **Logging Integration** (`LoggingIntegrationTool`) — Real-time log capture and buffer management

### Integration with Log4j2

Descartes integrates directly with Log4j2 runtime to provide guaranteed timestamp parsing and automatic log file discovery:

- Extracts timestamp patterns and formatters from Log4j2 layouts
- Discovers active and rolled log files from appender configurations
- Provides accurate timestamp parsing without regex guesswork

## Log File Discovery

**Purpose**: Discover log files from Log4j2 runtime configuration without manual path specification.

**Operations**:
- `list` — List all active log files with metadata (path, size, timestamp pattern)
- `appenders` — List Log4j2 appender configurations
- `discover` — Discover rolled log files for a specific file pattern

**Key Features**:
- Automatic discovery from Log4j2 FileAppender and RollingFileAppender configurations
- Extracts timestamp patterns from PatternLayout for guaranteed parsing
- Identifies rolled files using appender file patterns
- Returns file metadata: path, size, last modified, timestamp format

**Example Use Cases**:
- Discover all application log files without knowing paths
- Find rolled/archived logs from previous days
- Get timestamp patterns for accurate time-based queries

**Reference Implementation**: `com.bitsapplied.descartes.tools.logging.support.LogFileDiscoveryService`

## Log File Search & Analysis

**Purpose**: Comprehensive log analysis with bash-parity for remote debugging scenarios.

### Design Principles

**Agent-Friendly**:
- Simple defaults: 2-3 parameters for most operations
- Multi-file by default when file path omitted
- Smart context: `show_context: true` automatically adds 3 lines before/after
- Bandwidth optimization: count returns integer instead of full content

**Feature Parity with Bash**:
- Pattern matching (grep)
- Inverse matching (grep -v)
- Multi-pattern search (AND/OR modes)
- Line extraction (tail, head, range)
- Field extraction
- Content extraction between markers

**Remote-First Design**:
- Multi-file operations built-in (no manual loops)
- Bandwidth-conscious responses
- Automatic file discovery
- Progressive disclosure (simple → powerful)

### Operations

#### 1. Search (`search`/`grep`)

Pattern matching with optional context lines around matches.

**Key Parameters**:
- `pattern` — Regex pattern to search for
- `show_context` — Auto-add 3 lines before/after (smart default)
- `case_insensitive` — Case-insensitive matching
- `invert_match` — Exclude matching lines (grep -v)

**Multi-Pattern Search**:
- `patterns` — Array of patterns
- `pattern_mode` — `"any"` (OR) or `"all"` (AND)

**Example**: Find all ERROR lines with context
```json
{
  "operation": "search",
  "pattern": "ERROR",
  "show_context": true
}
```

**Returns**: Match results with line numbers, timestamps, log levels, and context.

#### 2. Count (`count`)

Count matches without returning content — bandwidth optimization.

**Key Benefit**: Returns single integer instead of potentially thousands of lines, saving 100x bandwidth.

**Example**: Count exceptions across all logs
```json
{
  "operation": "count",
  "pattern": "Exception"
}
```

**Returns**: Integer count per file for multi-file operations.

#### 3. Extract (`extract`)

Extract captured groups from regex patterns with optional deduplication.

**Key Parameters**:
- `pattern` — Regex with capture groups
- `capture_group` — Group number (0=full match, 1=first group, etc.)
- `unique` — Deduplicate extracted values

**Use Cases**:
- Extract user IDs from log lines
- Extract error codes
- Extract URLs or IP addresses
- Build lists of unique values

**Example**: Extract unique usernames
```json
{
  "operation": "extract",
  "pattern": "user=([\\w]+)",
  "unique": true
}
```

**Returns**: Array of extracted strings, optionally deduplicated.

#### 4. Between (`between`)

Extract content between start and end markers.

**Key Parameters**:
- `start_marker` — Start boundary regex
- `end_marker` — End boundary regex
- `include_markers` — Include boundary lines in results
- `max_sections` — Limit number of sections extracted

**Use Cases**:
- Extract stack traces (between exception line and next log line)
- Extract transaction blocks (BEGIN/COMMIT)
- Extract request/response pairs
- Extract multi-line error messages

**Example**: Extract stack traces
```json
{
  "operation": "between",
  "start_marker": "Exception:",
  "end_marker": "^\\d{4}-"
}
```

**Returns**: Array of sections with content, line numbers, and metadata.

#### 5. Timeline (`timeline`)

Time-series frequency analysis of pattern matches.

**Key Parameters**:
- `pattern` — Pattern to track
- `bucket_size` — Time bucket: `"5m"`, `"1h"`, `"30s"`, `"1d"`

**Use Cases**:
- Visualize error frequency over time
- Identify traffic spikes
- Correlate events temporally
- Generate time-series data for graphing

**Example**: ERROR frequency in 5-minute buckets
```json
{
  "operation": "timeline",
  "pattern": "ERROR",
  "bucket_size": "5m"
}
```

**Returns**: Map of timestamps to match counts.

#### 6. Tail (`tail`)

Get last N lines from log file(s).

**Example**: Last 100 lines
```json
{
  "operation": "tail",
  "lines": 100,
  "file_path": "app.log"
}
```

#### 7. Head (`head`)

Get first N lines from log file(s).

**Example**: First 50 lines
```json
{
  "operation": "head",
  "lines": 50
}
```

#### 8. Range (`range`)

Extract specific line number range (1-indexed, inclusive).

**Example**: Lines 100-200
```json
{
  "operation": "range",
  "start_line": 100,
  "end_line": 200
}
```

#### 9. Time Range (`time_range`)

Filter log entries by timestamp.

**Key Parameters**:
- `start_time` / `end_time` — ISO 8601 timestamps
- `since` — Relative time: `"1h"`, `"30m"`, `"2d"`

**Example**: Last hour's logs
```json
{
  "operation": "time_range",
  "since": "1h",
  "end_time": "2025-11-09T16:00:00Z"
}
```

### Multi-File Operations

**Automatic Multi-File**:
If no file specification is provided, searches all discovered `.log` files automatically.

**Explicit Multi-File**:
- `file_paths` — Array of specific file paths
- `file_pattern` — Glob pattern (e.g., `**/*.log`, `app-*.log`)

**Result Aggregation**:
Multi-file operations return per-file results with aggregated statistics.

**Example**: Search all application logs
```json
{
  "operation": "search",
  "pattern": "OutOfMemoryError"
}
```

**Example**: Specific pattern
```json
{
  "operation": "count",
  "file_pattern": "app-*.log",
  "pattern": "Exception"
}
```

### Advanced Features

#### Guaranteed Timestamp Parsing

When Log4j2 timestamp patterns are available, Descartes uses them for parsing instead of regex heuristics, ensuring:
- Accurate time-based filtering
- Reliable timeline bucketing
- Correct chronological ordering

#### Smart Context

The `show_context` flag automatically adds 3 lines before and after matches — no need to calculate optimal context sizes.

#### Inverse Matching (grep -v)

Filter out unwanted patterns:
```json
{
  "operation": "search",
  "pattern": "DEBUG",
  "invert_match": true
}
```

Shows all lines that DON'T contain "DEBUG".

#### Multi-Pattern Search

Combine multiple patterns with AND/OR logic:
```json
{
  "operation": "search",
  "patterns": ["ERROR", "Exception"],
  "pattern_mode": "all"
}
```

Finds lines matching ALL patterns (AND logic).

#### Level Filtering

Filter by log level:
```json
{
  "operation": "search",
  "pattern": "database",
  "level_filter": "ERROR"
}
```

Only shows ERROR-level lines containing "database".

## Real-Time Log Capture

**Purpose**: Capture log events in-memory for programmatic access.

The `LoggingIntegrationTool` provides real-time access to captured log events without file I/O.

**Operations**:
- `capture` — Get recent log events from in-memory buffer
- `clear` — Clear the capture buffer
- `level` — Change log levels dynamically

**Configuration**: Requires `InMemoryAppender` in `log4j2.properties`:
```properties
packages = com.bitsapplied.descartes.util
appender.inMemory.type = InMemoryAppender
appender.inMemory.name = INMEMORY
appender.inMemory.maxBufferSize = 500
rootLogger.appenderRefs = console, inMemory
rootLogger.appenderRef.inMemory.ref = INMEMORY
```

**Use Cases**:
- Recent errors without file access
- Real-time log monitoring
- Testing and validation
- Programmatic log access

## Workflow Examples

### Remote Debugging Scenario

**Context**: Claude Code runs on Machine A, application runs on Machine B. Bash tools cannot access logs on Machine B, only Descartes MCP tools can.

**Workflow**:
1. Discover available logs: `{"operation": "list"}`
2. Count errors: `{"operation": "count", "pattern": "ERROR"}`
3. Analyze error distribution: `{"operation": "timeline", "pattern": "ERROR", "bucket_size": "5m"}`
4. Get error details: `{"operation": "search", "pattern": "ERROR", "show_context": true}`
5. Extract error codes: `{"operation": "extract", "pattern": "code=([0-9]+)", "unique": true}`

### Stack Trace Analysis

**Goal**: Find and analyze exception stack traces.

```json
// 1. Find exceptions
{"operation": "search", "pattern": "Exception:"}

// 2. Extract full stack traces
{
  "operation": "between",
  "start_marker": "Exception:",
  "end_marker": "^\\d{4}-",
  "max_sections": 10
}

// 3. Count by exception type
{"operation": "extract", "pattern": "([\\w.]+Exception):", "unique": true}
```

### Performance Correlation

**Goal**: Correlate slow requests with time periods.

```json
// 1. Timeline of slow requests
{
  "operation": "timeline",
  "pattern": "duration=[0-9]{4,}",
  "bucket_size": "10m"
}

// 2. Extract slow request details during spike
{
  "operation": "time_range",
  "start_time": "2025-11-09T14:00:00Z",
  "end_time": "2025-11-09T14:10:00Z"
}

// 3. Extract slow endpoints
{
  "operation": "extract",
  "pattern": "endpoint=([\\w/]+).*duration=[0-9]{4,}",
  "unique": true
}
```

## Performance Characteristics

**Log File Discovery**:
- O(1) for Log4j2 appender enumeration
- No filesystem scanning required

**Search Operations**:
- Streaming I/O for memory efficiency
- Single-pass processing
- Early termination on max_results

**Multi-File Operations**:
- Parallel file processing (future enhancement)
- Per-file result limits prevent unbounded responses

**Bandwidth Optimization**:
- Count operation: ~20 bytes vs. 200KB for full results
- Extract with unique: deduplicated at source
- Timeline: bucketed aggregates, not raw data

## Troubleshooting

### No Files Discovered

**Symptom**: `list` operation returns empty array.

**Causes**:
- Log4j2 not initialized
- No FileAppender or RollingFileAppender configured
- Descartes started before logging configured

**Resolution**: Ensure Log4j2 initializes before calling discovery tools.

### Timestamp Parsing Failures

**Symptom**: Time-based operations return unexpected results.

**Causes**:
- Custom timestamp format not recognized
- Logs from different sources with different formats

**Resolution**: Log file discovery provides the detected timestamp pattern — verify it matches your logs.

### Large Result Sets

**Symptom**: Response truncated, `truncated: true` in result.

**Causes**:
- Query too broad
- max_results limit reached

**Resolution**:
- Use `count` to check match volume first
- Narrow pattern or add filters
- Use `level_filter` to reduce results
- Increase `max_results` if needed

### Multi-File Performance

**Symptom**: Multi-file operations slow.

**Causes**:
- Too many files matched
- Large files

**Resolution**:
- Use more specific `file_pattern`
- Add time filters to reduce data scanned
- Process files individually if needed

## Reference Implementation

**Core Classes**:
- `LogFileDiscoveryService` — Log4j2 integration and file discovery
- `LogFileSearchService` — Search operation implementation
- `SearchParams` — Parameter validation and builder pattern
- `SearchResult` — Type-safe result wrapper
- `LogLineParser` — Timestamp and level extraction

**MCP Tools**:
- `LogFileDiscoveryTool` — Discovery tool interface
- `LogFileSearchTool` — Search tool interface
- `LoggingIntegrationTool` — Real-time capture interface

**Location**: `com.bitsapplied.descartes.tools.logging`

## Further Reading

- [Tool Reference](tools.md) — Complete parameter schemas and response formats
- [Architecture](architecture.md) — System design and extension points
- [Quick Start](quick-start.md) — Running examples and workflows
