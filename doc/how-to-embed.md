# Embedding Descartes MCP

This guide walks through the exact steps required to embed Descartes inside any Java application. The same pattern works for Spring services, desktop apps, CLI utilities, or long‑running agents.

---

## 1. Add the dependency

```xml
<!-- pom.xml -->
<dependency>
  <groupId>com.bitsapplied</groupId>
  <artifactId>descartes-mcp</artifactId>
  <version>${descartes.version}</version>
</dependency>
```

If you want to expose the shaded agent JAR for hot reload or profiler recordings, keep using the existing Maven profiles (`mvn clean package` builds it automatically).

---

## 2. Bootstrap the runtime

Create a single `DescartesRuntime` for your process. It owns the debugger, profiler, hot reload service, and shared MCP context.

```java
import com.bitsapplied.descartes.runtime.DescartesRuntime;
import com.bitsapplied.descartes.runtime.adapters.DefaultDescartesHostAdapter;
import com.bitsapplied.descartes.profiler.ProfilerSettings;

public final class DescartesHolder implements AutoCloseable {
  private final DescartesRuntime runtime;

  public DescartesHolder(AppSettings appSettings, MetricsBridge metrics, Map<String,Object> context) {
    DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder()
        .withProfilerSettingsSupplier(() -> toProfilerSettings(appSettings))
        .withProfilerEnabledConsumer(enabled -> appSettings.setProfilerEnabled(enabled))
        .withMetricsCollector(metrics::forward)
        .withSharedContext(context)                    // everything you want tools/resources to see
        .build();

    this.runtime = DescartesRuntime.bootstrap(host);
  }

  public DescartesRuntime runtime() {
    return runtime;
  }

  private static ProfilerSettings toProfilerSettings(AppSettings settings) {
    return ProfilerSettings.builder()
        .enabled(settings.isProfilerEnabled())
        .storagePath(settings.getProfilerStorage())
        .maxStoredProfiles(settings.getProfilerRetention())
        .samplingIntervalMs(settings.getProfilerInterval())
        .build();
  }

  @Override
  public void close() {
    runtime.close();  // shuts down debugger executors + profiler scheduler
  }
}
```

**Key points**

- `DefaultDescartesHostAdapter` keeps the boilerplate tiny. Only supply overrides you care about (metrics, listeners, custom debugger executor, etc.).
- The shared context map is stored inside the runtime automatically, so all tools see `descartes.runtime`, `descartes.profiler`, and `descartes.debugger` entries alongside anything you add.
- `runtime.close()` is idempotent – call it from your shutdown hook or `AutoCloseable` scope.

---

## 3. Register MCP tools and resources

Most hosts already expose an `MCPServer`. Replace manual wiring with the runtime handles:

```java
import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;

public final class McpLauncher {
  private final MCPServer mcp;
  private final DescartesRuntime descartes;

  public McpLauncher(DescartesHolder holder, int port) {
    this.descartes = holder.runtime();
    this.mcp = new MCPServer(settingsProvider(), port);

    // Contribute the context entries (runtime + your own)
    descartes.contributeTo(mcp.getContext());

    // Grab shared services once
    ProfilerService profiler = descartes.profiler().service();
    DebuggerService debugger = descartes.debugger().service();
    DebuggerExecutor executor = descartes.debugger().executor();

    // Register the standard tool suite
    mcp.registerTool(new ProfilerStartTool(profiler));
    mcp.registerTool(new DebuggerSessionTool(debugger, executor));
    // ... add JShell, hot reload, logging, etc.

    // Register any resources you need (metrics, thread dumps, log readers…)
    // mcp.registerResource(new MetricsResource());
  }

  public void start() throws Exception {
    mcp.start();
  }

  public void stop() throws Exception {
    mcp.stop();
    descartes.close();
  }
}
```

You only need to create the profiler/debugger instances once. Multiple MCP tools (start/stop, hotspots, variables, etc.) can share the same `ProfilerService` and `DebuggerService`.

---

## 4. Expose your application state (optional)

Populate the shared context map with whatever services your tools should inspect:

```java
Map<String, Object> context = new ConcurrentHashMap<>();
context.put("app.serviceRegistry", serviceRegistry);
context.put("app.settings", appSettings);
context.put("app.metrics", metricsFacade);
```

With those entries:

- `object_inspector` can evaluate expressions like `app.serviceRegistry.findByName("payments")`.
- JShell sessions can reference `context.get("app.settings")`.
- Custom MCP tools/resources can pull the same objects directly from the map.

---

## 5. Launch and connect

1. Start your application – the runtime initialises lazily.
2. Run the built-in TCP adapter if your MCP client expects a command:
   ```bash
   MCP_PORT=9080 node config/mcp/mcp-tcp-adapter.js
   ```
3. Point Claude Desktop / VS Code / any MCP-aware client to the server.

All debugger, profiler, JShell, hot reload, and monitoring tools are now available without additional scaffolding.

---

## Advanced customisation

- **Custom debugger executor:**  
  `.withDebuggerExecutorSupplier(() -> new DebuggerExecutor(myThreadFactory))`

- **External metrics system:**  
  Supply your own `MetricsCollector` to increment counters/timers/gauges.

- **Shutting down gracefully:**  
  `DescartesRuntime.DebuggersHandle` automatically stops active sessions in `close()`, but you can add extra hooks via `.withDebuggerShutdownHook(...)`.

- **Multiple runtimes:**  
  Avoid creating more than one per JVM. If your architecture spins dedicated workers, share a singleton `DescartesRuntime` via dependency injection or a global holder.

---

## Next steps

- [doc/tools.md](tools.md) lists every MCP tool, its arguments, and response shape.
- [doc/debugger.md](debugger.md) explains JDWP setup, stepping, breakpoints, and evaluation.
- [doc/profiler.md](profiler.md) covers advanced JFR recording scenarios (auto-export, retention, flame graphs).

Once embedded, everything else is configuration. Update your `SettingsProvider`, refresh hot reload inclusion lists, and add whichever tools/resources suit your workflow. Descartes handles the heavy lifting. 
