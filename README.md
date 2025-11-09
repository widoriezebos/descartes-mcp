# Descartes MCP

Descartes MCP (often shortened to “DFescartes”) turns a live JVM into a Model Context Protocol surface that AI copilots can interrogate. It combines in-process debugging, observability, hot reload, and JFR profiling so you can reason about production-grade workloads without restarting the application.

> ⚠️ **Security** — JShell, object inspection, and hot reload execute with the JVM’s full permissions. Keep Descartes on trusted networks and development profiles only.

## Highlights

- **Runtime debugger** — JShell with session isolation, deep object inspection, exception capture, and Log4j2 control. See `doc/debugger.md`.
- **Flight-recorder profiler** — One-click recordings, hotspot analysis, call trees, and self-contained flame graphs powered by `ProfilerService`. See `doc/profiler.md`.
- **Hot class reload** — Agent-backed reloads with structural validation and detailed diagnostics. See `doc/hot-reload.md`.
- **Operational insight** — Thread/state analysis, system monitoring, memory pressure reports, and stack snapshots.
- **Resource registry** — Classpath, system properties, application context, metrics, and MBeans exposed as read-only MCP resources.

## Modes

| Capability | Embedded Mode | Proxy Mode |
|------------|---------------|------------|
| How it runs | Embed `MCPServer` inside the JVM you want to inspect (e.g., `SimpleMCPServerExample`) or wire it into your application lifecycle. | Run the Node-based adapter (`config/mcp/mcp-tcp-adapter.js`) alongside the server to satisfy clients that expect a spawned command. |
| Access to context | Directly inject `Map<String,Object>` entries before starting the server. | Transparent pass-through to the same embedded instance—no extra wiring required. |
| Live tooling | Supports JShell, object inspection, hot reload (`-javaagent`), and JFR profiling within the host JVM. | Identical feature set; the adapter simply forwards MCP traffic while adding resilience. |
| Connectivity | Clients connect straight to `host:port`. Best for local automation, bespoke integrations, and services with open ports. | Adapter owns the TCP session, retries with exponential backoff, queues requests, and emits capability-change notifications—ideal for Claude Desktop or remote IDEs. |
| Typical use | Teams integrating Descartes into first-party services or running dedicated MCP sidecars. | Developers who cannot expose the server port directly or need a CLI command for MCP clients. |

## Quick Start

1. **Build** the shaded distribution: `mvn clean package`
2. **Run** the example server: `mvn exec:java` (add `-Prun-with-agent` for hot reload)
3. *(Optional)* **Bridge** to IDE clients: `node config/mcp/mcp-tcp-adapter.js`

That’s enough to expose the full toolset on port `9080`. Integrate it into your own app by constructing `MCPServer` with your context map and registering the tools/resources you need.

## Documentation

- `doc/index.md` — master index for every guide.
- `doc/debugger.md` — JShell, sessions, object inspection, and diagnostics.
- `doc/profiler.md` — JFR workflows, profile types, exports, retention.
- `doc/hot-reload.md` — Agent requirements, validation rules, troubleshooting.
- `doc/tools.md` — Arguments and response formats for each MCP tool.
- `doc/adapter.md` — TCP adapter configuration and robustness features.
- `doc/architecture.md` — Runtime design, extension points, and project layout.

## Security Notes

- Assume arbitrary code execution whenever Descartes is running.
- Bind to localhost or guarded networks; add your own auth layer if exposing beyond the JVM host.
- Disable or remove the adapter in production builds that do not require remote debugging.
