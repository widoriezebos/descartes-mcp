# Descartes MCP

Descartes MCP turns a live JVM into a Model Context Protocol surface that AI agents can interrogate. It combines in-process debugging, observability, hot reload, and JFR profiling so you can reason about production-grade workloads without restarting the application.

> ⚠️ **Security** — JShell, object inspection, and hot reload execute with the JVM’s full permissions. Keep Descartes on trusted networks and development profiles only.

## Highlights

- **Runtime debugger** — JShell with session isolation, deep object inspection, exception capture, and Log4j2 control. See [doc/debugger.md](doc/debugger.md).
- **Flight-recorder profiler** — One-click recordings, hotspot analysis, call trees, and self-contained flame graphs powered by `ProfilerService`. See [doc/profiler.md](doc/profiler.md).
- **Hot class reload** — Agent-backed reloads with structural validation and detailed diagnostics. See [doc/hot-reload.md](doc/hot-reload.md).
- **Operational insight** — Thread/state analysis, system monitoring, memory pressure reports, and stack snapshots.
- **Resource registry** — Classpath, system properties, application context, metrics, and MBeans exposed as read-only MCP resources.

## Modes

| Capability | Embedded Mode | Proxy Mode |
|------------|---------------|------------|
| How it runs | Embed `MCPServer` inside the JVM you want to inspect (e.g., `SimpleMCPServerExample`) or wire it into your application lifecycle. | Launch the standalone JDWP proxy (`MCPRemoteDebugProxy`) to bridge MCP clients to a remote JVM with `-agentlib:jdwp=…` enabled. |
| Tool coverage | Full catalogue: debugger suite, JShell tools, hot reload, profiler, monitoring, logging, resources, etc. | JDWP-compatible set only: `debugger_*`, `thread_analyzer`, `object_inspector` (11 tools total). No JShell, profiler, monitoring, logging, or hot-reload support. |
| Access to application state | Inject any `Map<String,Object>` entries before starting the server so tools see your services directly. | Limited to debugger-managed state; remote targets cannot expose in-process objects through the proxy. |
| Connectivity | Clients connect straight to `host:port`. Best for local automation, bespoke integrations, and services with open ports. | Proxy listens on its own MCP port, maintains the JDWP socket, retries with exponential backoff, queues requests, and emits capability-change notifications—ideal for Claude Desktop or remote IDEs. |
| Typical use | Teams integrating Descartes into first-party services or running dedicated MCP sidecars with full observability. | Developers who only need the debugger against remote hosts or shared environments where embedding Descartes is not possible. |

> Proxy mode registers the debugger suite plus `thread_analyzer` and `object_inspector`. All other tools (JShell, hot reload, profiler, monitoring, logging, exception analysis, etc.) require embedded mode.

## Quick Start

**1. Build (optional)**  
Most scripts auto-build if the shaded JAR is missing, but you can pre-build with:
```bash
mvn clean package
```

**2. Embedded mode (full toolset)**  
Use the hot-reload script to start `SimpleMCPServerExample` with every tool enabled:
```bash
./run-with-hotreload.sh          # Interactive (press Enter to stop)
./run-with-hotreload.sh --continuous  # Runs until killed
```
- Automatically assembles the shaded JAR, adds the `-javaagent` flag, and picks a free MCP port (default 9080).
- Alternative (manual): `mvn compile exec:exec -Prun-with-agent` (requires the JAR to be present).

**3. Proxy mode (debugger-only)**  
Start the standalone JDWP bridge when you only need the debugger suite:
```bash
./run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005
```
- Connects MCP clients on port 9090 by default.
- Requires the target JVM to be launched with `-agentlib:jdwp=…`.
- Only the JDWP-compatible tools (`debugger_*`, `thread_analyzer`, `object_inspector`) are available in this mode.

**4. Connect an MCP client**  
- Direct TCP clients can connect to `localhost:<mcpPort>` immediately.  
- For clients that expect to spawn a command (e.g., Claude Desktop), point them at the TCP adapter:
  ```bash
  MCP_PORT=9080 node config/mcp/mcp-tcp-adapter.js   # use MCP_PORT=9090 for the proxy
  ```
  and configure it to reach your MCP server (embedded or proxy).

Integrate Descartes into your own app by constructing `MCPServer` with your context map and registering the tools/resources you need.

## Documentation

- [doc/index.md](doc/index.md) — master index for every guide.
- [doc/debugger.md](doc/debugger.md) — JShell, sessions, object inspection, and diagnostics.
- [doc/profiler.md](doc/profiler.md) — JFR workflows, profile types, exports, retention.
- [doc/hot-reload.md](doc/hot-reload.md) — Agent requirements, validation rules, troubleshooting.
- [doc/tools.md](doc/tools.md) — Arguments and response formats for each MCP tool.
- [doc/adapter.md](doc/adapter.md) — TCP adapter configuration and robustness features.
- [doc/architecture.md](doc/architecture.md) — Runtime design, extension points, and project layout.
- [doc/quick-start.md](doc/quick-start.md) — Launch scripts, proxy usage, and workflow demos.
- [doc/running.md](doc/running.md) — Script details, troubleshooting, and environment notes.

## Security Notes

- Assume arbitrary code execution whenever Descartes is running.
- Bind to localhost or guarded networks; add your own auth layer if exposing beyond the JVM host.
- Disable or remove the adapter in production builds that do not require remote debugging.
