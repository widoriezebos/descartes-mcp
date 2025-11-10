package com.bitsapplied.descartes.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.tools.ProfilerCallTreeTool;
import com.bitsapplied.descartes.profiler.tools.ProfilerExportTool;
import com.bitsapplied.descartes.profiler.tools.ProfilerHotspotsTool;
import com.bitsapplied.descartes.profiler.tools.ProfilerListTool;
import com.bitsapplied.descartes.profiler.tools.ProfilerStartTool;
import com.bitsapplied.descartes.profiler.tools.ProfilerStopTool;
import com.bitsapplied.descartes.resources.ApplicationContextResource;
import com.bitsapplied.descartes.resources.ClasspathResource;
import com.bitsapplied.descartes.resources.MBeanResource;
import com.bitsapplied.descartes.resources.MCPResourceHandler;
import com.bitsapplied.descartes.resources.MetricsResource;
import com.bitsapplied.descartes.resources.ResourceRegistry;
import com.bitsapplied.descartes.resources.SystemPropertiesResource;
import com.bitsapplied.descartes.resources.SystemPropertiesSecurityConfig;
import com.bitsapplied.descartes.resources.ThreadDumpResource;
import com.bitsapplied.descartes.settings.DefaultSettings;
import com.bitsapplied.descartes.settings.SettingsProvider;
import com.bitsapplied.descartes.tools.DebuggerBreakpointsTool;
import com.bitsapplied.descartes.tools.DebuggerEvaluateTool;
import com.bitsapplied.descartes.tools.DebuggerEventsTool;
import com.bitsapplied.descartes.tools.DebuggerSessionTool;
import com.bitsapplied.descartes.tools.DebuggerStackTraceTool;
import com.bitsapplied.descartes.tools.DebuggerStepTool;
import com.bitsapplied.descartes.tools.DebuggerThreadsTool;
import com.bitsapplied.descartes.tools.DebuggerVariablesTool;
import com.bitsapplied.descartes.tools.DebuggerWatchTool;
import com.bitsapplied.descartes.tools.HotClassReloadTool;
import com.bitsapplied.descartes.tools.JShellAsyncTool;
import com.bitsapplied.descartes.tools.JShellSessionTool;
import com.bitsapplied.descartes.tools.JShellTool;
import com.bitsapplied.descartes.tools.LogFileDiscoveryTool;
import com.bitsapplied.descartes.tools.LogFileSearchTool;
import com.bitsapplied.descartes.tools.MCPTool;
import com.bitsapplied.descartes.tools.MemoryAnalyzerTool;
import com.bitsapplied.descartes.tools.ObjectInspectorTool;
import com.bitsapplied.descartes.tools.ProcessInspectorTool;
import com.bitsapplied.descartes.tools.SystemMonitoringTool;
import com.bitsapplied.descartes.tools.ThreadAnalyzerTool;

/**
 * Convenience launcher that bridges a {@link DescartesRuntime} with an
 * {@link MCPServer}. Hosts can attach the runtime once and then opt-in to the
 * tool/resource suites they want without hand-written boilerplate.
 */
public final class McpServerLauncher {

  private enum RegistrationGroup {
    DEBUGGER_TOOLS, PROFILER_TOOLS, JSHELL_TOOLS, HOT_RELOAD_TOOLS, DIAGNOSTICS_TOOLS, LOGGING_TOOLS, INSPECTION_TOOLS,
    SYSTEM_RESOURCES, APPLICATION_CONTEXT_RESOURCE
  }

  private final DescartesRuntime runtime;
  private final MCPServer server;
  private final Map<String, Object> context;
  private final EnumSet<RegistrationGroup> registeredGroups = EnumSet.noneOf(RegistrationGroup.class);
  private final Map<String, ResourceRegistry> resourceRegistries = new ConcurrentHashMap<>();
  private final List<MCPTool> toolLog = new ArrayList<>();
  private final Map<String, List<MCPResourceHandler>> resourceLog = new LinkedHashMap<>();

  private McpServerLauncher(DescartesRuntime runtime, MCPServer server) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.server = Objects.requireNonNull(server, "server");
    runtime.contributeTo(server.getContext());
    this.context = server.getContext();
  }

  /**
   * Attaches the launcher to an existing MCP server instance.
   *
   * @param runtime runtime to expose
   * @param server  existing server
   * @return configured launcher
   */
  public static McpServerLauncher attach(DescartesRuntime runtime, MCPServer server) {
    return new McpServerLauncher(runtime, server);
  }

  /**
   * Creates a new MCP server using {@link DefaultSettings} and attaches the
   * launcher.
   *
   * @param runtime runtime to expose
   * @param port    server port
   * @return configured launcher
   */
  public static McpServerLauncher create(DescartesRuntime runtime, int port) {
    return create(runtime, new DefaultSettings(), port);
  }

  /**
   * Creates a new MCP server with the provided settings provider and attaches the
   * launcher.
   *
   * @param runtime  runtime to expose
   * @param settings settings provider to supply MCP configuration
   * @param port     server port
   * @return configured launcher
   */
  public static McpServerLauncher create(DescartesRuntime runtime, SettingsProvider settings, int port) {
    return create(runtime, settings, port, null);
  }

  /**
   * Creates a new MCP server with the provided settings and initial context map
   * before attaching the launcher. The supplied context map is reused, so pass a
   * thread-safe implementation if you plan to mutate it after launch.
   *
   * @param runtime  runtime to expose
   * @param settings settings provider to supply MCP configuration
   * @param port     server port
   * @param context  initial context entries (may be {@code null})
   * @return configured launcher
   */
  public static McpServerLauncher create(DescartesRuntime runtime, SettingsProvider settings, int port,
      Map<String, Object> context) {
    Objects.requireNonNull(settings, "settings");
    Map<String, Object> contextHolder = context == null ? new ConcurrentHashMap<>() : context;
    return attach(runtime, new MCPServer(settings, port, contextHolder));
  }

  /**
   * Creates a new MCP server with {@link DefaultSettings} and the provided
   * initial context map.
   *
   * @param runtime runtime to expose
   * @param port    server port
   * @param context initial context entries (may be {@code null})
   * @return configured launcher
   */
  public static McpServerLauncher create(DescartesRuntime runtime, int port, Map<String, Object> context) {
    return create(runtime, new DefaultSettings(), port, context);
  }

  /**
   * @return the underlying runtime for advanced customisation.
   */
  public DescartesRuntime runtime() {
    return runtime;
  }

  /**
   * @return the managed MCP server instance.
   */
  public MCPServer server() {
    return server;
  }

  /**
   * Registers a single tool with the underlying server.
   *
   * @param tool tool to register
   * @return launcher for chaining
   */
  public McpServerLauncher registerTool(MCPTool tool) {
    Objects.requireNonNull(tool, "tool");
    server.registerTool(tool);
    toolLog.add(tool);
    return this;
  }

  /**
   * Registers multiple tools in one call.
   *
   * @param tools tools to register
   * @return launcher for chaining
   */
  public McpServerLauncher registerTools(Collection<? extends MCPTool> tools) {
    Objects.requireNonNull(tools, "tools");
    tools.forEach(this::registerTool);
    return this;
  }

  /**
   * Registers the full debugger suite (sessions, breakpoints, threads, stack
   * traces, variables, watch expressions, evaluation, events).
   *
   * @return launcher for chaining
   */
  public McpServerLauncher registerDebuggerTools() {
    if (!registeredGroups.add(RegistrationGroup.DEBUGGER_TOOLS)) {
      return this;
    }

    DebuggerService debuggerService = runtime.debugger().service();
    DebuggerExecutor debuggerExecutor = runtime.debugger().executor();

    return registerTools(List.of(new DebuggerSessionTool(debuggerService, debuggerExecutor),
        new DebuggerBreakpointsTool(debuggerService, debuggerExecutor),
        new DebuggerStepTool(debuggerService, debuggerExecutor),
        new DebuggerThreadsTool(debuggerService, debuggerExecutor),
        new DebuggerStackTraceTool(debuggerService, debuggerExecutor),
        new DebuggerVariablesTool(debuggerService, debuggerExecutor),
        new DebuggerEvaluateTool(debuggerService, debuggerExecutor),
        new DebuggerWatchTool(debuggerService, debuggerExecutor), new DebuggerEventsTool(context)));
  }

  /**
   * Registers the profiler management suite (start/stop, hotspots, call tree,
   * list, export).
   *
   * @return launcher for chaining
   */
  public McpServerLauncher registerProfilerTools() {
    if (!registeredGroups.add(RegistrationGroup.PROFILER_TOOLS)) {
      return this;
    }

    // Auto-enable profiler when tools are registered
    runtime.profiler().setEnabled(true);

    ProfilerService profiler = runtime.profiler().service();

    return registerTools(
        List.of(new ProfilerStartTool(profiler), new ProfilerStopTool(profiler), new ProfilerHotspotsTool(profiler),
            new ProfilerCallTreeTool(profiler), new ProfilerListTool(profiler), new ProfilerExportTool(profiler)));
  }

  /**
   * Registers JShell tooling (interactive evaluation, async execution, and
   * session management).
   *
   * @return launcher for chaining
   */
  public McpServerLauncher registerJshellTools() {
    if (!registeredGroups.add(RegistrationGroup.JSHELL_TOOLS)) {
      return this;
    }

    return registerTools(
        List.of(new JShellTool(context), new JShellAsyncTool(context), new JShellSessionTool(context)));
  }

  /**
   * Registers the hot reload tool suite.
   *
   * @return launcher for chaining
   */
  public McpServerLauncher registerHotReloadTools() {
    if (!registeredGroups.add(RegistrationGroup.HOT_RELOAD_TOOLS)) {
      return this;
    }

    return registerTool(new HotClassReloadTool(context));
  }

  /**
   * Registers process/system diagnostics tooling (process inspector, system
   * metrics, thread analysis, heap inspection).
   *
   * @return launcher for chaining
   */
  public McpServerLauncher registerDiagnosticsTools() {
    if (!registeredGroups.add(RegistrationGroup.DIAGNOSTICS_TOOLS)) {
      return this;
    }

    return registerTools(List.of(new ProcessInspectorTool(), new SystemMonitoringTool(),
        new ThreadAnalyzerTool(context), new MemoryAnalyzerTool(context)));
  }

  /**
   * Registers log-oriented tooling (discovery + search).
   *
   * @return launcher for chaining
   */
  public McpServerLauncher registerLoggingTools() {
    if (!registeredGroups.add(RegistrationGroup.LOGGING_TOOLS)) {
      return this;
    }

    return registerTools(List.of(new LogFileDiscoveryTool(), new LogFileSearchTool()));
  }

  /**
   * Registers object inspection utilities.
   *
   * @return launcher for chaining
   */
  public McpServerLauncher registerInspectionTools() {
    if (!registeredGroups.add(RegistrationGroup.INSPECTION_TOOLS)) {
      return this;
    }

    return registerTool(new ObjectInspectorTool(context));
  }

  /**
   * Registers common system resources (classpath, system properties, metrics,
   * thread dumps, MBeans) under the {@code system://} namespace.
   *
   * @return launcher for chaining
   */
  public McpServerLauncher registerSystemResources() {
    return registerSystemResources(SystemPropertiesSecurityConfig.forDevelopment());
  }

  /**
   * Registers system resources with a custom system properties security
   * configuration.
   *
   * @param securityConfig security configuration for system properties exposure
   * @return launcher for chaining
   */
  public McpServerLauncher registerSystemResources(SystemPropertiesSecurityConfig securityConfig) {
    Objects.requireNonNull(securityConfig, "securityConfig");
    if (!registeredGroups.add(RegistrationGroup.SYSTEM_RESOURCES)) {
      return this;
    }

    return registerResourceHandlers("system", new ClasspathResource(), new SystemPropertiesResource(securityConfig),
        new MetricsResource(), new ThreadDumpResource(), new MBeanResource());
  }

  /**
   * Registers an application-context resource under the {@code app://} namespace
   * to expose the shared context map.
   *
   * @return launcher for chaining
   */
  public McpServerLauncher registerApplicationContextResource() {
    return registerApplicationContextResource("app");
  }

  /**
   * Registers an application-context resource under the specified namespace.
   *
   * @param namespace URI scheme to publish (e.g. {@code myapp})
   * @return launcher for chaining
   */
  public McpServerLauncher registerApplicationContextResource(String namespace) {
    Objects.requireNonNull(namespace, "namespace");
    if (!registeredGroups.add(RegistrationGroup.APPLICATION_CONTEXT_RESOURCE)) {
      return this;
    }

    return registerResourceHandlers(namespace, new ApplicationContextResource(context));
  }

  /**
   * Registers the supplied resource handlers under the given namespace. If the
   * namespace was not previously used, a {@link ResourceRegistry} is created and
   * attached automatically.
   *
   * @param namespace URI scheme to publish
   * @param handlers  resource handlers to register
   * @return launcher for chaining
   */
  public McpServerLauncher registerResourceHandlers(String namespace, MCPResourceHandler... handlers) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(handlers, "handlers");

    ResourceRegistry registry = resourceRegistries.computeIfAbsent(namespace, key -> {
      ResourceRegistry created = new ResourceRegistry(key);
      server.registerResource(created);
      resourceLog.putIfAbsent(key, new ArrayList<>());
      return created;
    });

    for (MCPResourceHandler handler : handlers) {
      registry.registerResource(Objects.requireNonNull(handler, "handler"));
      resourceLog.computeIfAbsent(namespace, _ -> new ArrayList<>()).add(handler);
    }
    return this;
  }

  /**
   * @return immutable snapshot of the tools registered via this launcher.
   */
  public List<MCPTool> registeredTools() {
    return List.copyOf(toolLog);
  }

  /**
   * @return immutable snapshot of resource handlers grouped by namespace.
   */
  public Map<String, List<MCPResourceHandler>> registeredResourceHandlers() {
    Map<String, List<MCPResourceHandler>> snapshot = new LinkedHashMap<>();
    resourceLog.forEach((namespace, handlers) -> snapshot.put(namespace, List.copyOf(handlers)));
    return Map.copyOf(snapshot);
  }
}
