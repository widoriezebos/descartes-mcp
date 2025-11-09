# Embedding Descartes MCP

The goal of the runtime layer is simple: expose Descartes' debugger, profiler,
JShell, and resource catalogue without writing bespoke glue code for every
project. This guide shows how to plug Descartes into any host JVM with minimal
plumbing.

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

The shaded agent (hot reload + profiler exports) is still built through
`mvn clean package` when you need it; no extra wiring required here.

---

## 2. Describe your host environment

Descartes needs a small amount of host-specific information: profiler
settings/telemetry and (optionally) custom debugger wiring. You can either
implement `DescartesHost` directly or configure the ready-made
`DefaultDescartesHostAdapter`.

```java
import com.bitsapplied.descartes.runtime.DescartesHost;
import com.bitsapplied.descartes.profiler.ProfilerSettings;

public final class MorpheusHost implements DescartesHost {
  private final ProfilerSettings profilerSettings;

  public MorpheusHost(ProfilerSettings profilerSettings) {
    this.profilerSettings = profilerSettings;
  }

  @Override
  public ProfilerIntegration profiler() {
    return new ProfilerIntegration() {
      @Override
      public ProfilerSettings settings() {
        return profilerSettings;
      }
    };
  }
}
```

Prefer a fluent builder? Use the adapter instead:

```java
import com.bitsapplied.descartes.runtime.adapters.DefaultDescartesHostAdapter;

DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder()
    .withProfilerSettingsSupplier(() -> profilerSettingsFromConfig())
    .withProfilerEnabledConsumer(appSettings::setProfilerEnabled)
    .withSharedContext(sharedContextMap)
    .build();
```

Both approaches return a `DescartesHost` instance that you pass to the runtime.

---

## 3. Bootstrap the runtime and MCP server

`DescartesRuntime` lazily initialises heavyweight services while
`McpServerLauncher` removes the boilerplate of wiring dozens of tools and
resources.

```java
import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.runtime.DescartesRuntime;
import com.bitsapplied.descartes.runtime.McpServerLauncher;
import com.bitsapplied.descartes.settings.DefaultSettings;

Map<String, Object> context = new ConcurrentHashMap<>();
context.put("morpheus.start", Instant.now());
context.put("morpheus.config", morpheusConfig);

try (DescartesRuntime runtime = DescartesRuntime.bootstrap(host)) {
  McpServerLauncher launcher = McpServerLauncher.create(runtime, new DefaultSettings(), 9080, context);

  launcher.registerDiagnosticsTools()
          .registerLoggingTools()
          .registerInspectionTools()
          .registerHotReloadTools()
          .registerJshellTools()
          .registerProfilerTools()
          .registerDebuggerTools()
          .registerSystemResources()
          .registerApplicationContextResource();

  MCPServer server = launcher.server();
  server.start();
  Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    try {
      server.stop();
    } catch (Exception ignore) {
    }
  }));
}
```

Every `register…` method is optional. Pick the suites you need—JShell without
debugging, profiling without hot reload, etc. You can also register individual
tools via `registerTool(...)`.

---

## 4. Share application context (optional)

Any object you place in the shared context map is available to tools, resources,
and JShell evaluation via `context.get("key")`. Typical entries include
configuration beans, service registries, and metrics adapters.

The launcher keeps track of the runtime handles as well, so your map will also
contain:

- `descartes.runtime` → the `DescartesRuntime` instance
- `descartes.profiler` → `DescartesRuntime.ProfilerHandle`
- `descartes.debugger` → `DescartesRuntime.DebuggerHandle`

---

## 5. Inspect what was registered

After configuration you can query the launcher for diagnostics or logging:

```java
launcher.registeredTools().forEach(tool -> log.info("Tool {}", tool.getToolName()));

launcher.registeredResourceHandlers()
    .forEach((namespace, handlers) -> handlers.forEach(handler ->
        log.info("Resource {}://{}", namespace, handler.getUriPath())));
```

---

## 6. Advanced customisation

- **Single tool/resource registration** – use `registerTool(tool)` and
  `registerResourceHandlers("custom", handler1, handler2)`.
- **Custom namespaces** – call `registerResourceHandlers("morpheus", …)` to expose your
  own resources alongside the system set registered by `registerSystemResources()`.
- **Alternative settings providers** – `McpServerLauncher.create(runtime,
  settingsProvider, port)` works with any `SettingsProvider` implementation.
- **Debugger/Profiler overrides** – implement `DescartesHost.DebuggerIntegration`
  or extend the builder with `.withDebuggerServiceSupplier(...)` when you need a
  custom executor or pre-configured service instance.

---

## Putting It Together: Minimal Example

The `SimpleMCPServerExample` class under `src/main/java/.../example` puts the
steps above into a runnable program. Run it with `mvn exec:java`, connect via
the TCP adapter (`node config/mcp/mcp-tcp-adapter.js`), and all the registered
tools/resources become available instantly.

---

Once Descartes is embedded you can concentrate on policy decisions: which tools
to expose in production, how to surface profiler recordings, and what context
objects help your engineers debug faster. The launcher and runtime handle the
rest.
