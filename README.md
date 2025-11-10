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
| How it runs | Embed `MCPServer` inside the application you want to inspect (e.g., `SimpleMCPServerExample`) or wire it into your application lifecycle. | Launch the standalone JDWP proxy (`MCPRemoteDebugProxy`) to bridge MCP clients to a remote JVM with `-agentlib:jdwp=…` enabled. |
| Tool coverage | Full catalogue: debugger suite, JShell tools, hot reload, profiler, monitoring, logging, resources, etc. | JDWP-compatible set only: `debugger_*`, `thread_analyzer`, `object_inspector` (11 tools total). No JShell, profiler, monitoring, logging, or hot-reload support. |
| Access to application state | Inject any `Map<String,Object>` entries before starting the server so tools see your services directly. | Limited to debugger-managed state; remote targets cannot expose in-process objects through the proxy. |
| Connectivity | Clients connect straight to `host:port`. Best for local automation, bespoke integrations, and services with open ports. | Proxy listens on its own MCP port, maintains the JDWP socket, retries with exponential backoff, queues requests, and emits capability-change notifications—ideal for Claude Code or remote IDEs. |
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
Choose how you want to expose the proxy:
- **Standalone server with auto-discovery** ✨ (recommended for local development)
  ```bash
  # Auto-discover by pattern
  ./run-remote-proxy.sh --auto-discover --process-pattern "myapp"

  # Auto-select if only one JDWP process exists
  ./run-remote-proxy.sh --auto-discover
  ```
- **Standalone server (explicit port)**
  ```bash
  ./run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005
  ```
- **Bundled with the TCP adapter (stdin/stdout transport for MCP clients)**
  ```bash
  ./run-proxy-adapter.sh --jdwp-host localhost --jdwp-port 5005
  # Or with auto-discovery:
  ./run-proxy-adapter.sh --auto-discover --process-pattern "myapp"
  ```
- Both variants listen on port 9090 by default and expect the target JVM to start with `-agentlib:jdwp=…`.
- Auto-discovery uses the Java Attach API to find JDWP processes on the same machine—no need to remember ports!
- Only the JDWP-compatible tools (`debugger_*`, `thread_analyzer`, `object_inspector`) are available in this mode.

**4. Connect an MCP client**
- Direct TCP clients can connect to `localhost:<mcpPort>` immediately.
- For clients that expect to spawn a command (e.g., Claude Code), use one of the TCP adapters:

  **Node.js adapter:**
  ```bash
  MCP_PORT=9080 node config/mcp/mcp-tcp-adapter.js   # use MCP_PORT=9090 for the proxy
  ```

  **Java adapter (no Node.js required):**
  ```bash
  MCP_PORT=9080 ./run-mcp-adapter.sh   # use MCP_PORT=9090 for the proxy
  ```

  See [config/mcp/ADAPTERS.md](config/mcp/ADAPTERS.md) for detailed adapter documentation and example configurations.

## Embedding in Your Application

**Minimal Complete Example:**

```java
import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.runtime.*;
import com.bitsapplied.descartes.runtime.adapters.DefaultDescartesHostAdapter;
import com.bitsapplied.descartes.settings.DefaultSettings;
import java.util.*;

// 1. Create host adapter with application context
Map<String, Object> context = new HashMap<>();
context.put("myService", yourService);  // Share app objects

DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder()
    .withSharedContext(context)
    .build();

// 2. Bootstrap runtime and launcher
try (DescartesRuntime runtime = DescartesRuntime.bootstrap(host)) {
  McpServerLauncher launcher = McpServerLauncher.create(runtime, new DefaultSettings(), 9080, context);

  // 3. Register tool suites (pick what you need)
  launcher.registerDebuggerTools()
          .registerJshellTools()
          .registerProfilerTools()  // Auto-enables profiler
          .registerSystemResources();

  // 4. Start server
  MCPServer server = launcher.server();
  server.start();
  Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
}
```

**That's it!** The profiler automatically enables when you call `registerProfilerTools()`. All settings have sensible defaults. Every `register*` method is optional—choose only the capabilities you need. Objects in the context map are accessible to all tools.

For detailed setup, configuration options, and advanced customization, see the [Embedding Guide](doc/how-to-embed.md).

## Documentation

- [doc/quick-start.md](doc/quick-start.md) — Launch scripts, proxy usage, and workflow demos.
- [doc/index.md](doc/index.md) — master index for every guide.
- [doc/architecture.md](doc/architecture.md) — Runtime design, extension points, and project layout.
- [doc/debugger.md](doc/debugger.md) — JShell, sessions, object inspection, and diagnostics.
- [doc/logging.md](doc/logging.md) — Comprehensive log analysis with bash-parity for remote debugging.
- [doc/profiler.md](doc/profiler.md) — JFR workflows, profile types, exports, retention.
- [doc/hot-reload.md](doc/hot-reload.md) — Agent requirements, validation rules, troubleshooting.
- [doc/tools.md](doc/tools.md) — Arguments and response formats for each MCP tool.
- [doc/adapter.md](doc/adapter.md) — TCP adapter configuration and robustness features.
- [doc/running.md](doc/running.md) — Script details, troubleshooting, and environment notes.

## Security Notes

- Assume arbitrary code execution whenever Descartes is running.
- Bind to localhost or guarded networks; add your own auth layer if exposing beyond the JVM host.
- Disable or remove the adapter in production builds that do not require remote debugging.
