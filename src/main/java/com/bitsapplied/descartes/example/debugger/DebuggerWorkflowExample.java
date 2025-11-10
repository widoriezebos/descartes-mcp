package com.bitsapplied.descartes.example.debugger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.example.debugger.scenarios.BasicDebuggingScenarios;
import com.bitsapplied.descartes.example.debugger.scenarios.BuggyCalculator;
import com.bitsapplied.descartes.example.debugger.scenarios.CallStackScenarios;
import com.bitsapplied.descartes.example.debugger.scenarios.ConcurrencyScenarios;
import com.bitsapplied.descartes.example.debugger.scenarios.DataStructureScenarios;
import com.bitsapplied.descartes.example.debugger.scenarios.ExceptionScenarios;
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
import com.bitsapplied.descartes.tools.MemoryAnalyzerTool;
import com.bitsapplied.descartes.tools.ObjectInspectorTool;
import com.bitsapplied.descartes.tools.ProcessInspectorTool;
import com.bitsapplied.descartes.tools.SystemMonitoringTool;
import com.bitsapplied.descartes.tools.ThreadAnalyzerTool;

/**
 * Comprehensive example demonstrating the Descartes Debugger workflow.
 *
 * <p>
 * This example showcases the complete debugging capability including:
 * <ul>
 * <li>Session management (start, stop, status)</li>
 * <li>Breakpoint operations (line, conditional, method)</li>
 * <li>Stepping operations (over, into, out)</li>
 * <li>Variable inspection (locals, expand objects, statics)</li>
 * <li>Expression evaluation (simple and complex)</li>
 * <li>Watch expressions (auto-evaluated on suspend)</li>
 * <li>Stack trace examination (filtered and full)</li>
 * <li>Thread control (suspend, resume, inspect)</li>
 * </ul>
 *
 * <h2>Usage Modes</h2>
 *
 * <h3>Automated Demo Mode (default)</h3> Runs through debugging scenarios
 * automatically with explanatory output:
 * 
 * <pre>
 * mvn exec:java -Dexec.mainClass="com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample"
 * </pre>
 *
 * <h3>Interactive Mode</h3> Keeps server running for manual MCP client
 * interaction:
 * 
 * <pre>
 * mvn exec:java -Dexec.mainClass="com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample" \
 *   -Dexec.args="--interactive"
 * </pre>
 *
 * <h2>What This Example Demonstrates</h2>
 *
 * <h3>1. Basic Debugging</h3>
 * <ul>
 * <li>Setting breakpoints and stepping through code</li>
 * <li>Inspecting variables at different points</li>
 * <li>Evaluating expressions in context</li>
 * <li>Using watch expressions to track values</li>
 * </ul>
 *
 * <h3>2. Bug Hunting</h3>
 * <ul>
 * <li>Finding off-by-one errors with watches</li>
 * <li>Detecting null pointer issues with conditional breakpoints</li>
 * <li>Identifying overflow with expression evaluation</li>
 * <li>Discovering logic errors through stepping</li>
 * </ul>
 *
 * <h3>3. Complex Data Structures</h3>
 * <ul>
 * <li>Expanding nested objects hierarchically</li>
 * <li>Inspecting collections (Lists, Maps, Sets)</li>
 * <li>Handling circular references</li>
 * <li>Examining static fields</li>
 * </ul>
 *
 * <h3>4. Concurrency Debugging</h3>
 * <ul>
 * <li>Listing and filtering threads</li>
 * <li>Detecting deadlocks automatically</li>
 * <li>Finding race conditions</li>
 * <li>Suspending and resuming specific threads</li>
 * </ul>
 *
 * <h3>5. Exception Analysis</h3>
 * <ul>
 * <li>Breaking on exception throw</li>
 * <li>Inspecting exception objects</li>
 * <li>Analyzing exception chains</li>
 * <li>Examining stack traces at exception point</li>
 * </ul>
 *
 * <h3>6. Call Stack Inspection</h3>
 * <ul>
 * <li>Navigating deep call chains</li>
 * <li>Debugging recursive methods</li>
 * <li>Frame-by-frame variable inspection</li>
 * <li>Stack filtering to hide framework code</li>
 * </ul>
 *
 * <h2>Demo Output Location</h2> All demo output and logs are saved to:
 * {@code ./debugger-demo-output/}
 *
 * <h2>Requirements</h2>
 * <ul>
 * <li>JDK 11+ (for JDWP support)</li>
 * <li>JDK 17+ requires:
 * {@code --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED}</li>
 * <li>Port 9080 available for MCP server</li>
 * </ul>
 *
 * <h2>See Also</h2>
 * <ul>
 * <li>{@link BasicDebuggingScenarios} - Simple debugging examples</li>
 * <li>{@link BuggyCalculator} - Intentional bugs to find</li>
 * <li>{@link DataStructureScenarios} - Complex object inspection</li>
 * <li>{@link ConcurrencyScenarios} - Multi-threaded debugging</li>
 * <li>{@link ExceptionScenarios} - Exception handling</li>
 * <li>{@link CallStackScenarios} - Stack trace analysis</li>
 * <li>README.md - Complete documentation</li>
 * </ul>
 */
public class DebuggerWorkflowExample {

  private static final int MCP_PORT = 9080;
  private static final Path DEMO_OUTPUT_PATH = Paths.get("./debugger-demo-output");

  private final MCPServer server;
  private final DebuggerService debuggerService;
  private final DebuggerExecutor debuggerExecutor;

  // Scenario instances
  private final BasicDebuggingScenarios basicScenarios;
  private final BuggyCalculator buggyCalculator;
  private final DataStructureScenarios dataScenarios;
  private final ConcurrencyScenarios concurrencyScenarios;
  private final ExceptionScenarios exceptionScenarios;
  private final CallStackScenarios callStackScenarios;

  public DebuggerWorkflowExample() {

    // Create scenario instances
    this.basicScenarios = new BasicDebuggingScenarios();
    this.buggyCalculator = new BuggyCalculator();
    this.dataScenarios = new DataStructureScenarios();
    this.concurrencyScenarios = new ConcurrencyScenarios();
    this.exceptionScenarios = new ExceptionScenarios();
    this.callStackScenarios = new CallStackScenarios();

    // Initialize debugger service and executor
    this.debuggerService = new DebuggerService();
    this.debuggerExecutor = new DebuggerExecutor();

    // Create context map with all scenarios
    Map<String, Object> context = new HashMap<>();
    context.put("basicScenarios", basicScenarios);
    context.put("buggyCalculator", buggyCalculator);
    context.put("dataScenarios", dataScenarios);
    context.put("concurrencyScenarios", concurrencyScenarios);
    context.put("exceptionScenarios", exceptionScenarios);
    context.put("callStackScenarios", callStackScenarios);
    context.put("demoOutputPath", DEMO_OUTPUT_PATH.toString());

    // Create MCP server
    DefaultSettings settings = new DefaultSettings();
    this.server = new MCPServer(settings, MCP_PORT, context);
    server.setServerName("Debugger Workflow Demo Server");
    server.setServerVersion("1.0.0");

    // Register monitoring tools
    server.registerTool(new ProcessInspectorTool());
    server.registerTool(new SystemMonitoringTool());
    server.registerTool(new ThreadAnalyzerTool(context));
    server.registerTool(new MemoryAnalyzerTool(context));
    server.registerTool(new LogFileDiscoveryTool());
    server.registerTool(new LogFileSearchTool());

    // Register JShell and introspection tools
    server.registerTool(new JShellTool(context));
    server.registerTool(new JShellAsyncTool(context));
    server.registerTool(new JShellSessionTool(context));
    server.registerTool(new ObjectInspectorTool(context));

    // Register hot reload tool (note: requires -javaagent to work)
    server.registerTool(new HotClassReloadTool(context));

    // Register all 8 debugger tools
    server.registerTool(new DebuggerSessionTool(debuggerService, debuggerExecutor));
    server.registerTool(new DebuggerBreakpointsTool(debuggerService, debuggerExecutor));
    server.registerTool(new DebuggerStepTool(debuggerService, debuggerExecutor));
    server.registerTool(new DebuggerThreadsTool(debuggerService, debuggerExecutor));
    server.registerTool(new DebuggerStackTraceTool(debuggerService, debuggerExecutor));
    server.registerTool(new DebuggerVariablesTool(debuggerService, debuggerExecutor));
    server.registerTool(new DebuggerEvaluateTool(debuggerService, debuggerExecutor));
    server.registerTool(new DebuggerWatchTool(debuggerService, debuggerExecutor));
    server.registerTool(new DebuggerEventsTool(context));
  }

  /**
   * Start the MCP server.
   */
  public void startServer() throws Exception {
    try {
      server.start();
      System.out.println("✓ MCP Server started on port " + MCP_PORT);
    } catch (BindException e) {
      System.err.println("ERROR: Port " + MCP_PORT + " is already in use.");
      System.err.println("Check with: lsof -i :" + MCP_PORT);
      throw e;
    }
  }

  /**
   * Stop the MCP server and clean up resources.
   */
  public void stopServer() {
    debuggerExecutor.shutdown();
    server.stop();
    System.out.println("✓ MCP Server stopped");
  }

  /**
   * Run automated demo mode.
   */
  public void runAutomatedDemo() {
    printDemoBanner();
    printToolsOverview();
    printWorkflowGuide();

    System.out.println("\n" + "=".repeat(80));
    System.out.println("RUNNING AUTOMATED DEMO");
    System.out.println("=".repeat(80) + "\n");

    try {
      // Run all scenarios
      basicScenarios.runAllScenarios();
      Thread.sleep(500);

      buggyCalculator.demonstrateBugs();
      Thread.sleep(500);

      dataScenarios.runAllScenarios();
      Thread.sleep(500);

      concurrencyScenarios.runAllScenarios();
      Thread.sleep(500);

      exceptionScenarios.runAllScenarios();
      Thread.sleep(500);

      callStackScenarios.runAllScenarios();
      Thread.sleep(500);

      System.out.println("\n" + "=".repeat(80));
      System.out.println("AUTOMATED DEMO COMPLETE");
      System.out.println("=".repeat(80) + "\n");

      printNextSteps();

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.out.println("Demo interrupted");
    }
  }

  /**
   * Run interactive mode (keep server running).
   */
  public void runInteractiveMode() {
    printDemoBanner();
    printToolsOverview();
    printInteractiveGuide();

    System.out.println("\n" + "=".repeat(80));
    System.out.println("INTERACTIVE MODE");
    System.out.println("=".repeat(80) + "\n");

    System.out.println("Server is running. Available scenarios:");
    System.out.println("  - basicScenarios      : Basic debugging operations");
    System.out.println("  - buggyCalculator     : Intentional bugs to find");
    System.out.println("  - dataScenarios       : Complex data structure inspection");
    System.out.println("  - concurrencyScenarios: Multi-threaded debugging");
    System.out.println("  - exceptionScenarios  : Exception handling");
    System.out.println("  - callStackScenarios  : Stack trace analysis");

    System.out.println("\nAccess scenarios via MCP tools or press Enter to stop...\n");

    // Wait for user input
    try {
      new BufferedReader(new InputStreamReader(System.in)).readLine();
    } catch (IOException e) {
      System.out.println("Error reading input: " + e.getMessage());
    }
  }

  // ============================================================================
  // Print helper methods
  // ============================================================================

  private void printDemoBanner() {
    System.out.println("\n" + "=".repeat(80));
    System.out.println("DESCARTES DEBUGGER WORKFLOW EXAMPLE");
    System.out.println("=".repeat(80));
    System.out.println("This example demonstrates comprehensive debugging capabilities using");
    System.out.println("the Descartes MCP debugger tools.");
    System.out.println("=".repeat(80) + "\n");
  }

  private void printToolsOverview() {
    System.out.println("Available Debugger Tools:");
    System.out.println("  1. debugger_session     - Start/stop debug sessions, manage lifecycle");
    System.out.println("  2. debugger_breakpoints - Set line/conditional/method breakpoints");
    System.out.println("  3. debugger_step        - Step over, step into, step out");
    System.out.println("  4. debugger_threads     - List, inspect, suspend, resume threads");
    System.out.println("  5. debugger_stacktrace  - Capture and examine stack frames");
    System.out.println("  6. debugger_variables   - Inspect locals, expand objects, view statics");
    System.out.println("  7. debugger_evaluate    - Evaluate expressions in context");
    System.out.println("  8. debugger_watch       - Auto-evaluated watch expressions\n");
  }

  private void printWorkflowGuide() {
    System.out.println("Typical Debugging Workflow:");
    System.out.println("  1. debugger_session: {operation: \"start\"}");
    System.out.println("  2. debugger_breakpoints: {operation: \"set\", class: \"...\", line: 42}");
    System.out.println("  3. Trigger code execution (call scenario methods)");
    System.out.println("  4. debugger_variables: {operation: \"getVariables\", thread_id: X, frame_index: 0}");
    System.out.println("  5. debugger_evaluate: {operation: \"evaluate\", thread_id: X, expression: \"x > 10\"}");
    System.out.println("  6. debugger_step: {operation: \"stepOver\", thread_id: X}");
    System.out.println("  7. debugger_threads: {operation: \"resume\", thread_id: X}");
    System.out.println("  8. debugger_session: {operation: \"stop\"}\n");
  }

  private void printInteractiveGuide() {
    System.out.println("\nInteractive Mode Usage:");
    System.out.println("  • Connect your MCP client to localhost:" + MCP_PORT);
    System.out.println("  • Start a debug session: debugger_session {operation: \"start\"}");
    System.out.println("  • Set breakpoints in scenario classes:");
    System.out.println("    - BasicDebuggingScenarios.simpleCalculation()");
    System.out.println("    - BuggyCalculator.sumToN_BUGGY()");
    System.out.println("    - DataStructureScenarios.objectHierarchy()");
    System.out.println("  • Trigger scenarios by calling their methods");
    System.out.println("  • Use debugger tools to inspect, step, evaluate");
    System.out.println("  • See README.md for complete workflow examples\n");
  }

  private void printNextSteps() {
    System.out.println("Next Steps:");
    System.out.println("  1. Review the scenario source code to understand each example");
    System.out.println("  2. Run in --interactive mode to try debugging manually:");
    System.out.println("     mvn exec:java -Dexec.mainClass=\"" + getClass().getName() + "\" \\");
    System.out.println("       -Dexec.args=\"--interactive\"");
    System.out.println("  3. Connect your MCP client (Claude Code, etc.) to port " + MCP_PORT);
    System.out.println("  4. Practice setting breakpoints, stepping, and inspecting variables");
    System.out.println("  5. Read README.md for detailed workflow examples and tips");
    System.out.println();
  }

  // ============================================================================
  // Main entry point
  // ============================================================================

  public static void main(String[] args) {
    // Parse arguments
    boolean interactiveMode = false;
    for (String arg : args) {
      if ("--interactive".equals(arg) || "-i".equals(arg)) {
        interactiveMode = true;
        break;
      }
    }

    DebuggerWorkflowExample example = new DebuggerWorkflowExample();

    // Start server
    try {
      example.startServer();
    } catch (Exception e) {
      System.err.println("Failed to start server: " + e.getMessage());
      System.exit(1);
    }

    // Register shutdown hook
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\nShutting down...");
      example.stopServer();
    }));

    // Run appropriate mode
    if (interactiveMode) {
      example.runInteractiveMode();
    } else {
      example.runAutomatedDemo();
    }

    // Clean shutdown
    example.stopServer();
    System.out.println("Goodbye!");
  }
}
