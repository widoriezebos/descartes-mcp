# Architecture

Descartes MCP turns a running JVM into a Model Context Protocol server. The implementation lives in `com.bitsapplied.descartes` and is organised around a small set of extensible building blocks.

## Runtime Model

- **MCPServer (`com.bitsapplied.descartes.MCPServer`)** accepts JSON-RPC 2.0 traffic over TCP (default port `9080`). It exposes `initialize`, `tools/list`, `tools/call`, `resources/list`, and `resources/read`, plus a simple `ping`.
- Every request shares a **context map** (`Map<String,Object>`) allowing tools and resources to reach arbitrarily complex application state without compile-time coupling.
- The server runs each client in a cached thread pool and keeps per-connection routing lightweight: it simply serialises/deserialises with Jackson and dispatches to registered tool/resource instances.
- Configuration is provided by a `SettingsProvider`. The default implementation (`DefaultSettings`) persists to `~/.descartes/settings.properties` so workspace preferences survive restarts.

## Tools and Resources

- Tools implement `MCPTool`. The built-in catalogue includes:
  - `JShellTool` and `JShellSessionTool` for in-process JShell evaluation and lifecycle control.
  - `ObjectInspectorTool` for reflective inspection using the shared context.
  - Diagnostics: `ProcessInspectorTool`, `SystemMonitoringTool`, `ThreadAnalyzerTool`, `MemoryAnalyzerTool`, `ExceptionAnalysisTool`, `LogFileDiscoveryTool`, and `LogFileSearchTool`.
  - Live code support: `HotClassReloadTool` and the profiler tools (`ProfilerStartTool`, `ProfilerStopTool`, `ProfilerHotspotsTool`, `ProfilerCallTreeTool`, `ProfilerListTool`, `ProfilerExportTool`).
- Resources implement `MCPResource` and expose read-only data. The default registry (`ResourceRegistry`) ships with:
  - `ClasspathResource`, `SystemPropertiesResource`, `MetricsResource`, `ThreadDumpResource`, `MBeanResource`, and `ApplicationContextResource`.
- Both tool and resource registries are additive: you can register your own implementations at runtime without touching `MCPServer`.

## Hot Reload Subsystem

- The `hotreload` package centres on `HotReloadService`. It coordinates with the Java agent entrypoints in `hotreload.agent.HotReloadAgent` (supports `premain` and `agentmain`) and tracks loaded classes through `ClassLoadTracker`.
- Bytecode compatibility checks rely on ASM via `analyzer.ClassStructureAnalyzer` and guard against JVM limitations (no new methods/fields, unchanged hierarchy, etc.).
- The same shaded JAR acts as both agent and application; pass `-javaagent:target/descartes-mcp-*-jar-with-dependencies.jar` to enable reloads.
- `HotReloadResult` captures per-class diagnostics so MCP responses can report what reloaded, what was skipped, and why.

## Profiler Subsystem

- `ProfilerService` manages Java Flight Recorder sessions. It enforces one active recording at a time, schedules auto-stop, and persists artefacts.
- `ProfilerSettings` controls defaults (storage path, sampling interval, which event types are enabled). Profiles are stored under `logs/profiles/` by default.
- `JFRRecorder` configures `jdk.jfr.Recording` instances, while `JFRParser` builds rich `ProfileSnapshot` objects.
- Insights are post-processed by `CallTreeBuilder`, `Hotspot` modelling, and exporters such as `FlameGraphExporter` for interactive HTML visualisation.

## Project Layout

```
src/main/java/com/bitsapplied/descartes/
├── MCPServer.java
├── example/                 # SimpleMCPServerExample starter
├── hotreload/               # Agent, service, analysers
├── profiler/                # Service, config, tools, exporters
├── resources/               # MCPResource implementations
├── settings/                # SettingsProvider implementations
├── tools/                   # MCPTool implementations
└── util/                    # JShell service, console capture, helpers
```

Tests mirror the structure under `src/test/java`, with fixtures in `src/test/resources`. Optional assets for adapters live in `config/mcp/`.

## Security Notes

- `JShellTool`, `JShellSessionTool`, and `ObjectInspectorTool` execute arbitrary Java inside the host JVM. Use only on trusted networks and development profiles.
- Hot reload and profiling require agent capabilities; avoid exposing the agent-enabled binary in production.
- Log file tools (`LogFileDiscoveryTool` and `LogFileSearchTool`) automatically discover and read log files from Log4j2 configuration. Ensure file permissions restrict access to appropriate users.
