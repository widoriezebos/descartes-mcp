package com.bitsapplied.descartes.example;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.ProfilerSettings;
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
import com.bitsapplied.descartes.runtime.DescartesRuntime;
import com.bitsapplied.descartes.runtime.adapters.DefaultDescartesHostAdapter;
import com.bitsapplied.descartes.settings.DefaultSettings;
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
 * Minimal example showing how to embed Descartes via {@link DescartesRuntime}.
 * <p>
 * The runtime wires the profiler/debugger services, exposes shared context
 * objects, and keeps resource registration consistent with the rest of the
 * codebase. The example mirrors the flow described in doc/how-to-embed.md: add
 * your app-specific objects to the shared context, bootstrap the runtime, and
 * register whichever tools/resources you want to expose.
 * </p>
 */
public final class SimpleMCPServerExample {

  private static final int DEFAULT_PORT = 9080;

  private SimpleMCPServerExample() {
  }

  public static void main(String[] args) {
    boolean continuousMode = shouldRunContinuously(args);

    DefaultSettings settings = new DefaultSettings();
    Map<String, Object> context = buildContext();

    ProfilerSettings profilerSettings = ProfilerSettings.builder().enabled(true).storagePath(Paths.get("logs/profiles"))
        .maxStoredProfiles(100).packageFilter("com.bitsapplied").cpuEnabled(true).samplingIntervalMs(10).build();

    DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder()
        .withProfilerSettingsSupplier(() -> profilerSettings).withSharedContext(context).build();

    try (DescartesRuntime runtime = DescartesRuntime.bootstrap(host)) {
      MCPServer server = new MCPServer(settings, DEFAULT_PORT, context);
      runtime.contributeTo(server.getContext());

      List<MCPTool> registeredTools = registerTools(server, runtime, context);
      List<MCPResourceHandler> registeredResources = registerResources(server, context);

      printSummary(registeredTools, registeredResources);

      try {
        runServerLoop(server, continuousMode);
      } finally {
        server.stop();
      }

    } catch (BindException bindEx) {
      System.err.printf(
          "ERROR: Failed to start MCP server on port %d. The port is already in use. Use 'lsof -i :%d' or 'netstat -ano | findstr :%d'%n",
          DEFAULT_PORT, DEFAULT_PORT, DEFAULT_PORT);
      System.exit(1);
    } catch (Exception ex) {
      System.err.println("ERROR: Failed to start MCP server: " + ex.getMessage());
      ex.printStackTrace();
      System.exit(1);
    }
  }

  private static Map<String, Object> buildContext() {
    Map<String, Object> context = new ConcurrentHashMap<>();
    context.put("example.settings", "default");
    context.put("example.startTime", System.currentTimeMillis());
    context.put("example.version", "1.0.0");
    context.put("exampleData",
        Map.of("name", "Descartes MCP Example", "features", List.of("JShell", "Monitoring", "Debugging", "Profiling")));
    return context;
  }

  private static List<MCPTool> registerTools(MCPServer server, DescartesRuntime runtime, Map<String, Object> context) {
    ProfilerService profiler = runtime.profiler().service();
    DebuggerService debuggerService = runtime.debugger().service();
    DebuggerExecutor debuggerExecutor = runtime.debugger().executor();

    List<MCPTool> tools = List.of(new ProcessInspectorTool(), new SystemMonitoringTool(),
        new ThreadAnalyzerTool(context), new MemoryAnalyzerTool(context), new LogFileDiscoveryTool(),
        new LogFileSearchTool(), new JShellTool(context), new JShellAsyncTool(context), new JShellSessionTool(context),
        new ObjectInspectorTool(context), new HotClassReloadTool(context), new ProfilerStartTool(profiler),
        new ProfilerStopTool(profiler), new ProfilerHotspotsTool(profiler), new ProfilerCallTreeTool(profiler),
        new ProfilerListTool(profiler), new ProfilerExportTool(profiler),
        new DebuggerSessionTool(debuggerService, debuggerExecutor),
        new DebuggerBreakpointsTool(debuggerService, debuggerExecutor),
        new DebuggerStepTool(debuggerService, debuggerExecutor),
        new DebuggerThreadsTool(debuggerService, debuggerExecutor),
        new DebuggerStackTraceTool(debuggerService, debuggerExecutor),
        new DebuggerVariablesTool(debuggerService, debuggerExecutor),
        new DebuggerEvaluateTool(debuggerService, debuggerExecutor),
        new DebuggerWatchTool(debuggerService, debuggerExecutor), new DebuggerEventsTool(context));

    tools.forEach(server::registerTool);
    return tools;
  }

  private static List<MCPResourceHandler> registerResources(MCPServer server, Map<String, Object> context) {
    ResourceRegistry registry = new ResourceRegistry("app");
    List<MCPResourceHandler> resources = List.of(new ClasspathResource(),
        new SystemPropertiesResource(SystemPropertiesSecurityConfig.forDevelopment()), new MetricsResource(),
        new ThreadDumpResource(), new MBeanResource(), new ApplicationContextResource(context));

    resources.forEach(registry::registerResource);
    server.registerResource(registry);
    return resources;
  }

  private static void printSummary(List<MCPTool> tools, List<MCPResourceHandler> resources) {
    System.out.println("\nRegistered tools:");
    tools.forEach(tool -> System.out.println("  - " + tool.getToolName() + " : " + tool.getToolDescription()));

    System.out.println("\nRegistered resources:");
    resources.forEach(
        resource -> System.out.println("  - app://" + resource.getUriPath() + " : " + resource.getDescription()));
  }

  private static void runServerLoop(MCPServer server, boolean continuousMode) throws Exception {
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\nShutting down MCP server...");
      server.stop();
    }));

    if (continuousMode) {
      System.out.println("\n=== Running in CONTINUOUS mode ===");
      System.out.println("Server will run continuously. Use Ctrl+C to stop.");
      while (!Thread.currentThread().isInterrupted()) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    } else {
      System.out.println("\n=== Running in INTERACTIVE mode ===");
      System.out.println("Press Enter to stop the server or Ctrl+C to force quit...");
      try {
        System.in.read();
      } catch (IOException ex) {
        System.out.println("Input unavailable. Continuing in continuous mode. Use Ctrl+C to stop.");
        while (!Thread.currentThread().isInterrupted()) {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }
    server.stop();
    System.out.println("Server stopped.");
  }

  private static boolean shouldRunContinuously(String[] args) {
    for (String arg : args) {
      if ("--continuous".equals(arg) || "-c".equals(arg)) {
        System.out.println("Continuous mode enabled via command line argument");
        return true;
      }
    }
    if ("true".equalsIgnoreCase(System.getProperty("descartes.continuous"))) {
      System.out.println("Continuous mode enabled via system property");
      return true;
    }
    if (System.console() == null) {
      System.out.println("No interactive console detected, enabling continuous mode");
      return true;
    }
    return false;
  }
}
