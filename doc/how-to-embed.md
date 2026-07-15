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
  <groupId>com.bitsapplied.descartes</groupId>
  <artifactId>descartes-mcp</artifactId>
  <version>1.0.2</version>
</dependency>
```

The shaded agent (hot reload + profiler exports) is still built through
`mvn clean package` when you need it; no extra wiring required here.

---

## 2. Describe your host environment

Descartes needs minimal host-specific information. The simplest approach is to
use the ready-made `DefaultDescartesHostAdapter` which provides sensible
defaults for everything.

### Start with defaults

The absolute minimum configuration:

```java
import com.bitsapplied.descartes.runtime.adapters.DefaultDescartesHostAdapter;

DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.defaults();
```

Most applications will want to share application context:

```java
DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder()
    .withSharedContext(sharedContextMap)
    .build();
```

**That's it!** The profiler automatically enables when you call
`McpServerLauncher.registerProfilerTools()` (see next section).

### Customize profiler settings (optional)

ProfilerSettings has sensible defaults. Only customize if you need to:

```java
import com.bitsapplied.descartes.profiler.ProfilerSettings;

DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder()
    .withProfilerSettings(
        ProfilerSettings.builder()
            .storagePath(Paths.get("custom/profiles"))  // Optional
            .maxStoredProfiles(50)  // Optional, default is 100
            .build()
    )
    .withSharedContext(sharedContextMap)
    .build();
```

**Default ProfilerSettings values:**
- `enabled = false` (auto-enabled when registerProfilerTools() is called)
- `storagePath = "logs/profiles"`
- `maxStoredProfiles = 100`
- `packageFilter = ""` (profiles all code; override to focus on specific packages)
- `samplingIntervalMs = 10` (10ms CPU sampling)
- `maxDurationSeconds = 300` (5 minute max recording)
- CPU profiling enabled, other events disabled

### Advanced: dynamic configuration

For runtime-controlled settings, use suppliers and consumers:

```java
DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder()
    .withProfilerSettingsSupplier(() -> loadSettingsFromConfig())
    .withProfilerEnabledConsumer(state -> appSettings.setProfilerEnabled(state))
    .withSharedContext(sharedContextMap)
    .build();
```

### Advanced: implement DescartesHost directly

For complete control, implement the `DescartesHost` interface:

```java
import com.bitsapplied.descartes.runtime.DescartesHost;
import com.bitsapplied.descartes.profiler.ProfilerSettings;

public final class MorpheusHost implements DescartesHost {
  @Override
  public ProfilerIntegration profiler() {
    return new ProfilerIntegration() {
      @Override
      public ProfilerSettings settings() {
        // Profiler auto-enables when registerProfilerTools() is called
        return ProfilerSettings.builder().build();
      }
    };
  }

  @Override
  public Map<String, Object> sharedContext() {
    return morpheusContextMap;
  }
}
```

All approaches return a `DescartesHost` instance that you pass to the runtime.

---

## 3. Bootstrap the runtime and MCP server

`DescartesRuntime` lazily initialises heavyweight services while
`McpServerLauncher` removes the boilerplate of wiring dozens of tools and
resources.

```java
import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.runtime.*;
import com.bitsapplied.descartes.runtime.adapters.DefaultDescartesHostAdapter;
import com.bitsapplied.descartes.settings.DefaultSettings;
import java.util.*;

// Create context with application objects
Map<String, Object> context = new HashMap<>();
context.put("morpheus.start", Instant.now());
context.put("morpheus.config", morpheusConfig);

// Configure host with shared context
DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder()
    .withSharedContext(context)
    .build();

// Bootstrap and start
try (DescartesRuntime runtime = DescartesRuntime.bootstrap(host)) {
  McpServerLauncher launcher = McpServerLauncher.create(runtime, new DefaultSettings(), 9080, context);

  launcher.registerDiagnosticsTools()
          .registerLoggingTools()
          .registerInspectionTools()
          .registerHotReloadTools()
          .registerJshellTools()
          .registerProfilerTools()  // Auto-enables profiler
          .registerDebuggerTools()
          .registerSystemResources()
          .registerApplicationContextResource();

  MCPServer server = launcher.server();
  server.start();
  Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
}
```

Every `register…` method is optional. Pick the suites you need—JShell without
debugging, profiling without hot reload, etc. The profiler automatically enables
when `registerProfilerTools()` is called. You can also register individual tools
via `registerTool(...)`.

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
