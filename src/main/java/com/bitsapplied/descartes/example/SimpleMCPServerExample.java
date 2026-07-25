package com.bitsapplied.descartes.example;

import java.io.IOException;
import java.net.BindException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.runtime.DescartesRuntime;
import com.bitsapplied.descartes.runtime.McpServerLauncher;
import com.bitsapplied.descartes.runtime.adapters.DefaultDescartesHostAdapter;
import com.bitsapplied.descartes.settings.DefaultSettings;

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

    DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder().withSharedContext(context).build();

    try (DescartesRuntime runtime = DescartesRuntime.bootstrap(host)) {
      McpServerLauncher launcher = McpServerLauncher.create(runtime, settings, DEFAULT_PORT, context);

      launcher.registerDiagnosticsTools().registerLoggingTools().registerInspectionTools().registerHotReloadTools()
          .registerJshellTools().registerProfilerTools().registerDebuggerTools().registerSystemResources()
          .registerApplicationContextResource();

      printSummary(launcher);

      MCPServer server = launcher.server();

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
    context.put("example.version", "1.0.3");
    context.put("exampleData",
        Map.of("name", "Descartes MCP Example", "features", List.of("JShell", "Monitoring", "Debugging", "Profiling")));
    return context;
  }

  private static void printSummary(McpServerLauncher launcher) {
    System.out.println("\nRegistered tools:");
    launcher.registeredTools()
        .forEach(tool -> System.out.println("  - " + tool.getToolName() + " : " + tool.getToolDescription()));

    System.out.println("\nRegistered resources:");
    launcher.registeredResourceHandlers().forEach((namespace, handlers) -> handlers.forEach(resource -> System.out
        .println("  - " + namespace + "://" + resource.getUriPath() + " : " + resource.getDescription())));
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
