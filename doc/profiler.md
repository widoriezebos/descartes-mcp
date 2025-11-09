# Profiler

Descartes exposes Java Flight Recorder (JFR) through a coordinated set of MCP tools. The implementation lives in `com.bitsapplied.descartes.profiler` and is designed for low-overhead production-safe captures (0.5%–2%).

## Requirements

- Runtime must be **JDK 11 or newer**; older JDKs do not ship JFR.
- Enable profiling in code by constructing `ProfilerService` with `ProfilerSettings.enabled(true)`.
- The shaded distribution stores profiles under `logs/profiles/` unless a different `storagePath` is supplied.
- Only one recording can be active at a time; attempts to start another session return an error.

## Profile Presets

`ProfilerStartTool` accepts a `profile_type` parameter that maps to `ProfilerConfig` presets:

| Type | Events Enabled | Sampling Interval | Typical Use |
|------|----------------|-------------------|-------------|
| `cpu` | CPU samples | 10 ms | Hot path analysis |
| `allocation` | Allocation profiling | 10 ms | Heap churn, leaks |
| `comprehensive` | CPU, allocation, locks, I/O, GC | 10 ms | Deep investigations (~2% overhead) |
| `lightweight` | CPU samples | 20 ms | Background monitoring (~0.5% overhead) |

You can also override `package_filter` to focus on application code.

## MCP Tools

| Tool | Class | Purpose | Key Arguments |
|------|-------|---------|---------------|
| `profiler_start` | `ProfilerStartTool` | Begin a recording and return a profile ID. | `duration_seconds` (10–300), `profile_type`, `package_filter` |
| `profiler_stop` | `ProfilerStopTool` | Stop an active recording immediately. | `profile_id` |
| `profiler_hotspots` | `ProfilerHotspotsTool` | Rank methods by CPU, allocation, or lock contention. | `profile_id`, `hotspot_type`, `top_n`, `min_percentage` |
| `profiler_call_tree` | `ProfilerCallTreeTool` | Explore aggregated call trees rooted at stack frames or method patterns. | `profile_id`, `method_pattern`, `max_depth` |
| `profiler_list` | `ProfilerListTool` | Inspect stored/active sessions, durations, and metadata. | *None* |
| `profiler_export` | `ProfilerExportTool` | Export snapshots as `json`, `text`, or `flamegraph` (self-contained HTML). | `profile_id`, `format` |

All responses are JSON strings produced by Jackson. Errors surface as structured payloads (`success: false`, `error`, optional `suggestion`).

## Typical Workflow

1. **Start profiling**  
   ```json
   {
     "name": "profiler_start",
     "arguments": {
       "duration_seconds": 45,
       "profile_type": "cpu",
       "package_filter": "com.example"
     }
   }
   ```
   Returns a `profile_id` (e.g., `27-03-2024_14.05.18-profile-af42c7d1`).

2. **Wait or stop early**  
   - Auto-stop occurs after the requested duration.
   - Call `profiler_stop` with the same ID to cut the session short.

3. **Inspect hotspots**  
   ```json
   {
     "name": "profiler_hotspots",
     "arguments": {
       "profile_id": "27-03-2024_14.05.18-profile-af42c7d1",
       "hotspot_type": "cpu",
       "top_n": 15
     }
   }
   ```
   Results include `percentage`, `samples`, `class_name`, `method_name`, and source coordinates when available.

4. **Dive into the call tree**  
   Filter to specific packages/methods using `method_pattern` (regex applied to fully qualified names).

5. **Export**  
   ```json
   {
     "name": "profiler_export",
     "arguments": {
       "profile_id": "27-03-2024_14.05.18-profile-af42c7d1",
       "format": "flamegraph"
     }
   }
   ```
   The response contains a filesystem path to the generated HTML for offline inspection.

## Storage & Retention

- `ProfileStore` maintains both the `.jfr` file and the parsed `ProfileSnapshot`. It evicts least-recently-used profiles beyond the configured `maxStoredProfiles`.
- `profiler_list` exposes retention metrics so you can monitor available disk headroom.
- When running without a writable filesystem, provide an in-memory store or adjust `ProfilerSettings.storagePath`.

## Integrations

- **Metrics**: `MetricsCollector` callbacks allow you to feed counters/latencies into an external system (use `MetricsCollector.NOOP` to disable).
- **Events**: Implement `ProfilerListener` to receive notifications when recordings start, stop, or fail.
- **Hot reload**: Profiles continue to work after reloading classes because recordings track class metadata dynamically.

## Troubleshooting

- `"Profiler is disabled"` — Ensure `ProfilerSettings.enabled(true)` and register the tools with the same service instance.
- `"JFR not available"` — Double-check you are running on a full JDK (not a JRE) version 11 or newer.
- `"Profiling session already in progress"` — Only one session can run simultaneously; stop the current one first.
- Large outputs — use `top_n` and `min_percentage` filters, or export a flame graph for offline browsing instead of streaming huge JSON payloads.
