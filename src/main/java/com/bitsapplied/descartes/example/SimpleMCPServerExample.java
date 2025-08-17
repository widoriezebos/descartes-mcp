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
 */
public class SimpleMCPServerExample {

  public static void main(String[] args) {
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

    System.out.println("\nPress Enter to stop the server or Ctrl+C to force quit...");

    // Keep the server running
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\nShutting down MCP server...");
      server.stop();
    }));

    // Keep main thread alive - wait for user input (works in IDEs)
    try {
      System.in.read(); // This will wait for Enter key in console
      System.out.println("Stopping server...");
    } catch (IOException e) {
      System.err.println("Error reading from console: " + e.getMessage());
    }

    // Clean shutdown
    server.stop();
    System.out.println("Server stopped.");
  }
}