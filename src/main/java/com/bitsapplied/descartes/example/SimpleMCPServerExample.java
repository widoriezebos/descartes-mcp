package com.bitsapplied.descartes.example;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.resources.ApplicationContextResource;
import com.bitsapplied.descartes.resources.ClasspathResource;
import com.bitsapplied.descartes.resources.MBeanResource;
import com.bitsapplied.descartes.resources.MCPResourceHandler;
import com.bitsapplied.descartes.resources.MetricsResource;
import com.bitsapplied.descartes.resources.ResourceRegistry;
import com.bitsapplied.descartes.resources.SystemPropertiesResource;
import com.bitsapplied.descartes.resources.ThreadDumpResource;
import com.bitsapplied.descartes.settings.DefaultSettings;
import com.bitsapplied.descartes.tools.ExceptionAnalysisTool;
import com.bitsapplied.descartes.tools.HotClassReloadTool;
import com.bitsapplied.descartes.tools.JShellSessionTool;
import com.bitsapplied.descartes.tools.JShellTool;
import com.bitsapplied.descartes.tools.LoggingIntegrationTool;
import com.bitsapplied.descartes.tools.MCPTool;
import com.bitsapplied.descartes.tools.MemoryAnalyzerTool;
import com.bitsapplied.descartes.tools.ObjectInspectorTool;
import com.bitsapplied.descartes.tools.ProcessInspectorTool;
import com.bitsapplied.descartes.tools.SystemMonitoringTool;
import com.bitsapplied.descartes.tools.ThreadAnalyzerTool;

/**
 * Example showing how to use Descartes as a standalone MCP server. This can be
 * easily integrated into any Java application.
 * 
 * <h3>Running this example:</h3>
 * 
 * <pre>
 * # Standard mode (no hot reload)
 * mvn exec:java
 * 
 * # With hot reload support - RECOMMENDED for development
 * mvn compile exec:exec -Prun-with-agent
 * 
 * # Manual with hot reload
 * java -javaagent:target/descartes-mcp-*-jar-with-dependencies.jar \
 *      -jar target/descartes-mcp-*-jar-with-dependencies.jar
 * </pre>
 * 
 * <h3>Mode detection:</h3> The server automatically detects the environment and
 * chooses the appropriate mode:
 * <ul>
 * <li>Interactive mode: When running in a terminal, waits for Enter key to
 * stop</li>
 * <li>Continuous mode: When running in IDE/background, runs continuously until
 * killed</li>
 * </ul>
 * 
 * <h3>Forcing continuous mode:</h3>
 * <ul>
 * <li>Command line: {@code java ... SimpleMCPServerExample --continuous}</li>
 * <li>System property: {@code -Ddescartes.continuous=true}</li>
 * <li>Maven profile: {@code mvn compile exec:exec -Prun-with-agent} (sets
 * continuous by default)</li>
 * <li>Eclipse IDE: Add {@code -Ddescartes.continuous=true} to VM arguments</li>
 * </ul>
 * 
 * <h3>Hot Reload Support:</h3> When run with the {@code run-with-agent} profile
 * or with {@code -javaagent} flag, the HotClassReloadTool becomes functional,
 * allowing you to reload Java classes at runtime without restarting the server.
 * See {@link HotClassReloadTool} and HOT_RELOAD_GUIDE.md for details.
 */
public class SimpleMCPServerExample {

  public static void main(String[] args) {
    // Determine if we should run in continuous mode
    boolean continuousMode = shouldRunContinuously(args);
    // Step 1: Create settings (can be file-based or in-memory)
    DefaultSettings settings = new DefaultSettings();

    // Step 2: Create context map for application-specific objects
    Map<String, Object> context = new HashMap<>();
    // Add any application-specific objects to the context
    // For this example, let's add some sample objects
    context.put("example.settings", settings);
    context.put("example.startTime", System.currentTimeMillis());
    context.put("example.version", "1.0.0");

    // Add some example objects that can be accessed from JShell
    context.put("context", context); // Allow JShell to access the context itself
    context.put("exampleData", Map.of("name", "Descartes MCP Example", "features",
        List.of("JShell", "Monitoring", "Debugging"), "debug", true));

    // In a real application, you would add your services, repositories, etc.
    // context.put("myapp.service", myService);
    // context.put("myapp.database", database);

    // Step 3: Create MCP server
    int port = 9080; // Use same port as Morpheus MCP server
    MCPServer server = new MCPServer(settings, port, context);

    // Step 4: Configure server identity (optional)
    server.setServerName("My Application MCP Server");
    server.setServerVersion("1.0.0");

    // Step 5: Register tools you want to expose
    List<MCPTool> tools = new ArrayList<>();
    // Debugging and monitoring tools
    tools.add(new ProcessInspectorTool());
    tools.add(new SystemMonitoringTool());
    tools.add(new ThreadAnalyzerTool());
    tools.add(new MemoryAnalyzerTool());
    tools.add(new ExceptionAnalysisTool());
    tools.add(new LoggingIntegrationTool());

    // Interactive JShell and inspection tools
    tools.add(new JShellTool(context));
    tools.add(new JShellSessionTool(context));
    tools.add(new ObjectInspectorTool(context));

    // Hot reload tool (requires agent to be loaded)
    tools.add(new HotClassReloadTool(context));

    for (MCPTool tool : tools) {
      server.registerTool(tool);
    }

    // Step 6: Register resources
    // Create a resource registry with a custom URI scheme
    ResourceRegistry resourceRegistry = new ResourceRegistry("app");

    // Register all the built-in resources
    List<MCPResourceHandler> resources = new ArrayList<>();
    resources.add(new ClasspathResource());
    resources.add(new SystemPropertiesResource());
    resources.add(new MetricsResource());
    resources.add(new ThreadDumpResource());
    resources.add(new MBeanResource());
    resources.add(new ApplicationContextResource(context));

    for (MCPResourceHandler resource : resources) {
      resourceRegistry.registerResource(resource);
    }

    // Register the resource registry with the server
    server.registerResource(resourceRegistry);

    // Step 7: Register custom resources (optional)
    // server.registerResource(new MyCustomResource());

    // Step 8: Start the server with proper error handling
    try {
      server.start();
      System.out.println("MCP Server started successfully on port " + port);
    } catch (java.net.BindException e) {
      System.err.println("ERROR: Failed to start MCP server on port " + port);
      System.err.println("Port " + port + " is already in use. Please check if another instance is running.");
      System.err.println("You can check with: lsof -i :" + port + " (on Mac/Linux) or netstat -ano | findstr :" + port
          + " (on Windows)");
      System.exit(1);
    } catch (Exception e) {
      System.err.println("ERROR: Failed to start MCP server: " + e.getClass().getName() + ": " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }

    // List available tools
    System.out.println("\nAvailable tools:");
    for (MCPTool tool : tools) {
      System.out.println("  - " + tool.getToolName() + " - " + tool.getToolDescription());
    }

    // List available resources
    System.out.println("\nAvailable resources:");
    for (MCPResourceHandler resource : resources) {
      System.out.println("  - app://" + resource.getUriPath() + " - " + resource.getDescription());
    }

    // Print JShell usage examples
    System.out.println("\n=== JShell Tool Usage Examples ===");
    System.out.println("The JShell tools allow you to execute Java code interactively:");
    System.out.println();
    System.out.println("1. Simple execution (jshell_repl):");
    System.out.println("   Code: System.out.println(\"Hello from JShell!\");");
    System.out.println("   Code: 2 + 2");
    System.out.println();
    System.out.println("2. Access context objects:");
    System.out.println("   Code: var data = (Map) context.get(\"exampleData\");");
    System.out.println("   Code: System.out.println(\"App name: \" + data.get(\"name\"));");
    System.out.println();
    System.out.println("3. Session management (jshell_session_manager):");
    System.out.println("   Use session_id to maintain state across calls");
    System.out.println("   Action: close, extend_expiry, session_count");
    System.out.println();
    System.out.println("4. Object inspection (object_inspector):");
    System.out.println("   Expression: context.get(\"exampleData\")");
    System.out.println("   Operation: inspect, fields, methods, type, value");
    System.out.println();
    System.out.println("5. Hot Class Reload (hot_reload_classes):");
    System.out.println(
        "   NOTE: Requires JVM to be started with -javaagent:target/descartes-mcp-*-jar-with-dependencies.jar");
    System.out.println("   Package Filter: com.bitsapplied.descartes.*");
    System.out.println("   See HOT_RELOAD_GUIDE.md for detailed usage");

    // Register shutdown hook for graceful shutdown
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\nShutting down MCP server...");
      server.stop();
    }));

    // Keep the server running based on the mode
    if (continuousMode) {
      System.out.println("\n=== Running in CONTINUOUS mode ===");
      System.out.println("Server will run continuously. Use Ctrl+C to stop.");

      // Keep the server running indefinitely
      while (!Thread.currentThread().isInterrupted()) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    } else {
      System.out.println("\n=== Running in INTERACTIVE mode ===");
      System.out.println("Press Enter to stop the server or Ctrl+C to force quit...");

      // Wait for user input
      try {
        System.in.read(); // This will wait for Enter key in console
        System.out.println("Stopping server...");
      } catch (IOException e) {
        // If stdin is not available or closed, fall back to continuous mode
        System.out.println("Input not available, continuing in background mode...");
        System.out.println("Use Ctrl+C to stop.");

        while (!Thread.currentThread().isInterrupted()) {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }

    // Clean shutdown
    server.stop();
    System.out.println("Server stopped.");
  }

  /**
   * Determines if the server should run in continuous mode.
   * 
   * Continuous mode is selected when: 1. --continuous or -c command line argument
   * is present 2. System property descartes.continuous=true is set 3. No
   * interactive console is available (System.console() == null)
   * 
   * @param args Command line arguments
   * @return true if should run continuously, false for interactive mode
   */
  private static boolean shouldRunContinuously(String[] args) {
    // Check command line arguments
    for (String arg : args) {
      if ("--continuous".equals(arg) || "-c".equals(arg)) {
        System.out.println("Continuous mode enabled via command line argument");
        return true;
      }
    }

    // Check system property (useful for IDE configurations)
    if ("true".equalsIgnoreCase(System.getProperty("descartes.continuous"))) {
      System.out.println("Continuous mode enabled via system property");
      return true;
    }

    // Auto-detect: if no console is available, use continuous mode
    if (System.console() == null) {
      System.out.println("No interactive console detected, enabling continuous mode");
      return true;
    }

    // Default to interactive mode when console is available
    return false;
  }
}