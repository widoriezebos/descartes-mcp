package com.bitsapplied.descartes.example.profiler;

import java.io.Console;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.example.profiler.workloads.AllocationWorkload;
import com.bitsapplied.descartes.example.profiler.workloads.ComputationWorkload;
import com.bitsapplied.descartes.example.profiler.workloads.ConcurrencyWorkload;
import com.bitsapplied.descartes.example.profiler.workloads.IOWorkload;
import com.bitsapplied.descartes.profiler.MetricsCollector;
import com.bitsapplied.descartes.profiler.ProfilerListener;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.ProfilerSettings;
import com.bitsapplied.descartes.profiler.tools.ProfilerCallTreeTool;
import com.bitsapplied.descartes.profiler.tools.ProfilerExportTool;
import com.bitsapplied.descartes.profiler.tools.ProfilerHotspotsTool;
import com.bitsapplied.descartes.profiler.tools.ProfilerListTool;
import com.bitsapplied.descartes.profiler.tools.ProfilerStartTool;
import com.bitsapplied.descartes.profiler.tools.ProfilerStopTool;
import com.bitsapplied.descartes.settings.DefaultSettings;

/**
 * Comprehensive example demonstrating the Descartes Profiler workflow.
 *
 * This example showcases the complete profiling capability including: -
 * Different profile types (CPU, allocation, comprehensive, lightweight) -
 * Realistic workload generation (computation, memory, concurrency, I/O) -
 * Profiler tool usage (start, stop, hotspots, call tree, list, export) - Flame
 * graph generation and interpretation - Performance analysis workflow
 *
 * <h2>Usage Modes</h2>
 *
 * <h3>Automated Demo Mode (default)</h3> Runs through all profiling scenarios
 * automatically with explanatory output:
 * 
 * <pre>
 * mvn exec:java -Dexec.mainClass="com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample"
 * </pre>
 *
 * <h3>Interactive Mode</h3> Keeps server running for manual MCP client
 * interaction:
 * 
 * <pre>
 * mvn exec:java -Dexec.mainClass="com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample" \
 *   -Dexec.args="--interactive"
 * </pre>
 *
 * <h2>What This Example Demonstrates</h2>
 *
 * <h3>1. CPU Profiling</h3> - Identifies computation hotspots (recursive
 * algorithms, loops) - Shows method call hierarchies in call trees - Generates
 * CPU flame graphs showing time distribution - ~1% overhead suitable for
 * production
 *
 * <h3>2. Allocation Profiling</h3> - Finds memory allocation hotspots -
 * Identifies memory-intensive code paths - Detects allocation anti-patterns
 * (String concatenation, collection resizing) - Helps prevent memory leaks and
 * excessive GC
 *
 * <h3>3. Comprehensive Profiling</h3> - Captures CPU, allocation, locks, I/O,
 * and GC events - Shows complete performance picture - Identifies different
 * bottleneck types - ~2% overhead - suitable for staging/pre-prod
 *
 * <h3>4. Flame Graph Generation</h3> - Interactive HTML flame graphs with zoom
 * and search - Visual performance analysis - Color-coded by package/class -
 * Self-contained single-file output
 *
 * <h2>Profile Output Location</h2> All profiles and flame graphs are saved to:
 * {@code ./profiler-demo-output/}
 *
 * <h2>Requirements</h2> - JDK 11+ (for JFR support) - Port 9080 available for
 * MCP server - ~500MB disk space for profile storage - Modern web browser for
 * viewing flame graphs
 *
 * @see ComputationWorkload For CPU-intensive workload details
 * @see AllocationWorkload For memory allocation patterns
 * @see ConcurrencyWorkload For lock contention scenarios
 * @see IOWorkload For I/O operation patterns
 */
public class ProfilerWorkflowExample {

  private static final int MCP_PORT = 9080;
  private static final Path PROFILE_STORAGE_PATH = Paths.get("./profiler-demo-output");

  private final MCPServer server;
  private final ProfilerService profilerService;

  // Workload generators
  private final ComputationWorkload computationWorkload;
  private final AllocationWorkload allocationWorkload;
  private final ConcurrencyWorkload concurrencyWorkload;
  private final IOWorkload ioWorkload;

  // Mode flags
  private final boolean interactiveMode;

  public ProfilerWorkflowExample(boolean interactiveMode) {
    this.interactiveMode = interactiveMode;

    printHeader();

    // Initialize workloads
    computationWorkload = new ComputationWorkload();
    allocationWorkload = new AllocationWorkload();
    concurrencyWorkload = new ConcurrencyWorkload();
    ioWorkload = new IOWorkload();

    // Configure profiler
    profilerService = createProfilerService();

    // Create MCP server
    server = createMCPServer();

    System.out.println("ProfilerWorkflowExample initialized");
    System.out.println("Profile storage: " + PROFILE_STORAGE_PATH.toAbsolutePath());
    System.out.println();
  }

  /**
   * Creates and configures the ProfilerService.
   */
  private ProfilerService createProfilerService() {
    ProfilerSettings settings = ProfilerSettings.builder().enabled(true).storagePath(PROFILE_STORAGE_PATH)
        .maxStoredProfiles(50) // Keep up to 50 profiles for demo
        .packageFilter("com.bitsapplied.") // Focus on our code
        .cpuEnabled(true).samplingIntervalMs(10).build();

    return new ProfilerService(settings, ProfilerListener.NOOP, MetricsCollector.NOOP);
  }

  /**
   * Creates and configures the MCP server with all profiler tools.
   */
  private MCPServer createMCPServer() {
    // Create context with workload objects
    Map<String, Object> context = new HashMap<>();
    context.put("computationWorkload", computationWorkload);
    context.put("allocationWorkload", allocationWorkload);
    context.put("concurrencyWorkload", concurrencyWorkload);
    context.put("ioWorkload", ioWorkload);
    context.put("profilerService", profilerService);

    // Create server with DefaultSettings
    DefaultSettings settings = new DefaultSettings();
    MCPServer mcpServer = new MCPServer(settings, MCP_PORT, context);

    // Register all profiler tools
    mcpServer.registerTool(new ProfilerStartTool(profilerService));
    mcpServer.registerTool(new ProfilerStopTool(profilerService));
    mcpServer.registerTool(new ProfilerHotspotsTool(profilerService));
    mcpServer.registerTool(new ProfilerCallTreeTool(profilerService));
    mcpServer.registerTool(new ProfilerListTool(profilerService));
    mcpServer.registerTool(new ProfilerExportTool(profilerService));

    System.out.println("Registered 6 profiler tools:");
    System.out.println("   - profiler_start: Start profiling session");
    System.out.println("   - profiler_stop: Force-stop active profiling");
    System.out.println("   - profiler_hotspots: Analyze performance hotspots");
    System.out.println("   - profiler_call_tree: Examine method call hierarchies");
    System.out.println("   - profiler_list: List stored profiles");
    System.out.println("   - profiler_export: Export profiles as flame graphs");

    return mcpServer;
  }

  /**
   * Runs the automated demo workflow.
   */
  public void runAutomatedDemo() {
    try {
      System.out.println("Starting Automated Profiler Demo");
      System.out.println("=".repeat(80));
      System.out.println();

      // Start MCP server
      server.start();
      System.out.println("MCP Server started on port " + MCP_PORT);
      System.out.println();

      // Run demo scenarios
      demonstrateCPUProfiling();
      demonstrateAllocationProfiling();
      demonstrateComprehensiveProfiling();
      demonstrateProfileComparison();

      System.out.println("=".repeat(80));
      System.out.println("Automated Demo Complete!");
      System.out.println();
      printSummary();

    } catch (Exception e) {
      System.err.println("ERROR: Error during demo: " + e.getMessage());
      e.printStackTrace();
    } finally {
      cleanup();
    }
  }

  /**
   * Runs in interactive mode - keeps server running for manual interaction.
   */
  public void runInteractive() {
    try {
      System.out.println("Starting Interactive Mode");
      System.out.println("=".repeat(80));
      System.out.println();

      // Start MCP server
      server.start();
      System.out.println("MCP Server running on port " + MCP_PORT);
      System.out.println();

      // Start background workloads
      System.out.println("Starting background workloads...");
      computationWorkload.startContinuousLoad();
      allocationWorkload.startContinuousLoad();
      concurrencyWorkload.startContinuousLoad();
      ioWorkload.startContinuousLoad();
      System.out.println("All workloads running");
      System.out.println();

      printInteractiveInstructions();

      // Wait for user interrupt
      System.out.println("Press Enter to stop server and exit...");
      Console console = System.console();
      if (console != null) {
        console.readLine();
      } else {
        // Running in IDE or non-interactive environment
        System.out.println("Running in continuous mode (no console detected)");
        System.out.println("Use Ctrl+C to stop");
        Thread.currentThread().join(); // Wait forever
      }

    } catch (Exception e) {
      System.err.println("ERROR: Error in interactive mode: " + e.getMessage());
      e.printStackTrace();
    } finally {
      cleanup();
    }
  }

  /**
   * Demonstrates CPU profiling workflow.
   */
  private void demonstrateCPUProfiling() throws Exception {
    System.out.println("DEMO 1: CPU Profiling");
    System.out.println("-".repeat(80));
    System.out.println();
    System.out.println("CPU profiling identifies computation hotspots - methods consuming");
    System.out.println("the most CPU time. Useful for finding performance bottlenecks.");
    System.out.println();
    System.out.println("Profile type: CPU (sampling every 10ms, ~1% overhead)");
    System.out.println("Duration: 15 seconds");
    System.out.println("Workload: Recursive Fibonacci, prime generation, matrix math");
    System.out.println();

    // Start computation workload
    System.out.println("Starting CPU-intensive workload...");
    computationWorkload.startContinuousLoad();
    Thread.sleep(2000); // Let it warm up

    // Start profiling
    System.out.println("Starting CPU profile (15s)...");
    String profileId = profilerService.startProfiling(Duration.ofSeconds(15));
    System.out.println("   Profile ID: " + profileId);
    System.out.println();

    // Show progress
    for (int i = 1; i <= 15; i++) {
      Thread.sleep(1000);
      System.out.print("   Profiling: " + i + "s / 15s");
      if (i % 3 == 0) {
        System.out.print(" (operations: " + computationWorkload.getTotalOperations() + ")");
      }
      System.out.println();
    }

    // Stop workload
    computationWorkload.stop();
    Thread.sleep(2000); // Wait for profile to be processed

    // Analyze results
    System.out.println();
    System.out.println("Analyzing CPU hotspots...");
    System.out.println();
    System.out.println("In a real scenario, you would use the MCP tools:");
    System.out.println("   profiler_hotspots(profile_id=\"" + profileId + "\", limit=10)");
    System.out.println();
    System.out.println("Expected hotspots:");
    System.out.println("   1. ComputationWorkload.recursiveFibonacci() - Deep recursion");
    System.out.println("   2. ComputationWorkload.isPrime() - Hot loop");
    System.out.println("   3. ComputationWorkload.multiplyCell() - Nested loops");
    System.out.println("   4. MessageDigest.digest() - Native crypto operations");
    System.out.println();

    // Export flame graph
    String flameGraphPath = PROFILE_STORAGE_PATH.resolve(profileId + "-cpu-flamegraph.html").toString();
    System.out.println("Generating flame graph: " + flameGraphPath);
    System.out.println();
    System.out.println("In a real scenario, you would use:");
    System.out.println("   profiler_export(profile_id=\"" + profileId + "\", format=\"flamegraph\",");
    System.out.println("                   output=\"" + flameGraphPath + "\")");
    System.out.println();
    System.out.println("Flame Graph Interpretation:");
    System.out.println("   • Width = Time spent in method (wider = more CPU time)");
    System.out.println("   • Height = Call stack depth (shows method hierarchy)");
    System.out.println("   • Click to zoom into specific call paths");
    System.out.println("   • Search to highlight specific methods");
    System.out.println("   • Hover for detailed statistics and percentages");
    System.out.println();

    pause();
  }

  /**
   * Demonstrates allocation profiling workflow.
   */
  private void demonstrateAllocationProfiling() throws Exception {
    System.out.println("DEMO 2: Allocation Profiling");
    System.out.println("-".repeat(80));
    System.out.println();
    System.out.println("Allocation profiling tracks memory allocations to find");
    System.out.println("memory-intensive code paths and potential memory leaks.");
    System.out.println();
    System.out.println("Profile type: Allocation");
    System.out.println("Duration: 20 seconds");
    System.out.println("Workload: String concatenation, collections, serialization");
    System.out.println();

    // Start allocation workload
    System.out.println("Starting allocation-intensive workload...");
    allocationWorkload.startContinuousLoad();
    Thread.sleep(2000);

    // Start profiling
    System.out.println("Starting allocation profile (20s)...");
    String profileId = profilerService.startProfiling(Duration.ofSeconds(20));
    System.out.println("   Profile ID: " + profileId);
    System.out.println();

    // Show progress
    for (int i = 1; i <= 20; i++) {
      Thread.sleep(1000);
      System.out.print("   Profiling: " + i + "s / 20s");
      if (i % 4 == 0) {
        System.out.print(" (allocation cycles: " + allocationWorkload.getTotalAllocations() + ")");
      }
      System.out.println();
    }

    // Stop workload
    allocationWorkload.stop();
    Thread.sleep(2000);

    // Analyze results
    System.out.println();
    System.out.println("Analyzing allocation hotspots...");
    System.out.println();
    System.out.println("Expected allocation hotspots:");
    System.out.println("   1. AllocationWorkload.stringConcatenationAntipattern()");
    System.out.println("      - Shows O(n²) String allocation behavior");
    System.out.println("   2. AllocationWorkload.createLargeObjects()");
    System.out.println("      - Large byte[] allocations visible");
    System.out.println("   3. AllocationWorkload.collectionChurning()");
    System.out.println("      - ArrayList internal array resizing");
    System.out.println("   4. AllocationWorkload.streamApiOperations()");
    System.out.println("      - Stream intermediate object allocations");
    System.out.println();

    // Export flame graph
    String flameGraphPath = PROFILE_STORAGE_PATH.resolve(profileId + "-allocation-flamegraph.html").toString();
    System.out.println("Allocation flame graph would show:");
    System.out.println("   " + flameGraphPath);
    System.out.println();
    System.out.println("In allocation flame graphs:");
    System.out.println("   • Width = Bytes allocated (not CPU time)");
    System.out.println("   • Identify excessive allocations causing GC pressure");
    System.out.println("   • Find opportunities for object reuse");
    System.out.println("   • Detect memory leaks (methods allocating continuously)");
    System.out.println();

    pause();
  }

  /**
   * Demonstrates comprehensive profiling with all event types.
   */
  private void demonstrateComprehensiveProfiling() throws Exception {
    System.out.println("DEMO 3: Comprehensive Profiling");
    System.out.println("-".repeat(80));
    System.out.println();
    System.out.println("Comprehensive profiling captures ALL performance events:");
    System.out.println("   • CPU sampling (computation bottlenecks)");
    System.out.println("   • Memory allocations (GC pressure)");
    System.out.println("   • Lock contention (synchronization issues)");
    System.out.println("   • I/O operations (wait times)");
    System.out.println("   • Garbage collection (GC pauses)");
    System.out.println();
    System.out.println("Profile type: Comprehensive (~2% overhead)");
    System.out.println("Duration: 30 seconds");
    System.out.println("Workload: ALL workloads simultaneously (realistic scenario)");
    System.out.println();

    // Start all workloads
    System.out.println("Starting all workloads...");
    computationWorkload.startContinuousLoad();
    allocationWorkload.startContinuousLoad();
    concurrencyWorkload.startContinuousLoad();
    ioWorkload.startContinuousLoad();
    Thread.sleep(2000);

    // Start profiling
    System.out.println("Starting comprehensive profile (30s)...");
    String profileId = profilerService.startProfiling(Duration.ofSeconds(30));
    System.out.println("   Profile ID: " + profileId);
    System.out.println();

    // Show progress with statistics
    for (int i = 1; i <= 30; i++) {
      Thread.sleep(1000);
      System.out.print("   Profiling: " + i + "s / 30s");
      if (i % 5 == 0) {
        System.out.print(" [CPU: " + computationWorkload.getTotalOperations() + ", Alloc: "
            + allocationWorkload.getTotalAllocations() + ", Concur: " + concurrencyWorkload.getTotalOperations()
            + ", I/O: " + ioWorkload.getTotalOperations() + "]");
      }
      System.out.println();
    }

    // Stop workloads
    computationWorkload.stop();
    allocationWorkload.stop();
    concurrencyWorkload.stop();
    ioWorkload.stop();
    Thread.sleep(2000);

    // Analyze results
    System.out.println();
    System.out.println("Comprehensive profile captures multiple dimensions:");
    System.out.println();
    System.out.println("CPU Hotspots:");
    System.out.println("   - ComputationWorkload methods (heavy computation)");
    System.out.println("   - MessageDigest operations (crypto overhead)");
    System.out.println();
    System.out.println("Allocation Hotspots:");
    System.out.println("   - String concatenation (excessive String objects)");
    System.out.println("   - Collection operations (internal array allocations)");
    System.out.println();
    System.out.println("Lock Contention:");
    System.out.println("   - ConcurrencyWorkload.contentedSynchronizedMethod()");
    System.out.println("   - Thread wait times visible (BLOCKED states)");
    System.out.println();
    System.out.println("I/O Operations:");
    System.out.println("   - File write/read operations (wait time)");
    System.out.println("   - Buffered vs unbuffered I/O differences");
    System.out.println();

    // Export flame graph
    String flameGraphPath = PROFILE_STORAGE_PATH.resolve(profileId + "-comprehensive-flamegraph.html").toString();
    System.out.println("Comprehensive flame graph: " + flameGraphPath);
    System.out.println();
    System.out.println("This flame graph shows the COMPLETE performance picture:");
    System.out.println("   • Identify whether bottlenecks are CPU, memory, locks, or I/O");
    System.out.println("   • See interactions between different performance aspects");
    System.out.println("   • Most realistic view of application behavior");
    System.out.println();

    pause();
  }

  /**
   * Demonstrates profile comparison and listing.
   */
  private void demonstrateProfileComparison() throws Exception {
    System.out.println("DEMO 4: Profile Management");
    System.out.println("-".repeat(80));
    System.out.println();
    System.out.println("Profiler stores profiles for later analysis and comparison.");
    System.out.println();

    System.out.println("Listing stored profiles:");
    System.out.println("   Use: profiler_list()");
    System.out.println();
    System.out.println("You can:");
    System.out.println("   • Compare before/after optimization");
    System.out.println("   • Analyze different workload scenarios");
    System.out.println("   • Export multiple flame graphs for comparison");
    System.out.println("   • Store up to 50 profiles (configurable via ProfilerSettings)");
    System.out.println();

    System.out.println("Profile Types Comparison:");
    System.out.println();
    System.out.println("┌─────────────┬──────────┬─────────────────────────────────┐");
    System.out.println("│ Type        │ Overhead │ Use Case                        │");
    System.out.println("├─────────────┼──────────┼─────────────────────────────────┤");
    System.out.println("│ lightweight │ ~0.5%    │ Production monitoring           │");
    System.out.println("│ cpu         │ ~1%      │ Finding computation bottlenecks │");
    System.out.println("│ allocation  │ ~1.5%    │ Memory leak investigation       │");
    System.out.println("│ comprehnsiv │ ~2%      │ Deep investigation (staging)    │");
    System.out.println("└─────────────┴──────────┴─────────────────────────────────┘");
    System.out.println();
  }

  /**
   * Prints interactive mode instructions.
   */
  private void printInteractiveInstructions() {
    System.out.println("=".repeat(80));
    System.out.println("INTERACTIVE MODE - MCP Tool Usage");
    System.out.println("=".repeat(80));
    System.out.println();
    System.out.println("Connect using Claude Desktop or MCP client to localhost:" + MCP_PORT);
    System.out.println();
    System.out.println("Example workflow:");
    System.out.println();
    System.out.println("1. Start profiling:");
    System.out.println("   profiler_start(duration=30, profile_type=\"cpu\")");
    System.out.println();
    System.out.println("2. Wait for auto-stop or manually stop:");
    System.out.println("   profiler_stop()");
    System.out.println();
    System.out.println("3. List profiles:");
    System.out.println("   profiler_list()");
    System.out.println();
    System.out.println("4. Analyze hotspots:");
    System.out.println("   profiler_hotspots(profile_id=\"<id-from-list>\", limit=10)");
    System.out.println();
    System.out.println("5. Examine call tree:");
    System.out.println("   profiler_call_tree(profile_id=\"<id>\", method=\"<hotspot-method>\")");
    System.out.println();
    System.out.println("6. Export flame graph:");
    System.out.println("   profiler_export(profile_id=\"<id>\", format=\"flamegraph\",");
    System.out.println("                   output=\"./my-profile.html\")");
    System.out.println();
    System.out.println("7. Open the HTML file in your browser to explore interactively!");
    System.out.println();
    System.out.println("=".repeat(80));
    System.out.println();
  }

  /**
   * Prints summary of demo results.
   */
  private void printSummary() {
    System.out.println("Summary");
    System.out.println("-".repeat(80));
    System.out.println();
    System.out.println("Profile output directory: " + PROFILE_STORAGE_PATH.toAbsolutePath());
    System.out.println();
    System.out.println("Generated profiles:");
    System.out.println("   • CPU profile (15s) - computation hotspots");
    System.out.println("   • Allocation profile (20s) - memory allocation patterns");
    System.out.println("   • Comprehensive profile (30s) - complete performance picture");
    System.out.println();
    System.out.println("Next steps:");
    System.out.println("   1. Run in interactive mode: --interactive");
    System.out.println("   2. Connect Claude Desktop via MCP adapter (see config/mcp/)");
    System.out.println("   3. Use profiler tools to analyze stored profiles");
    System.out.println("   4. Generate and view flame graphs in browser");
    System.out.println();
    System.out.println("For more information:");
    System.out.println("   • README.md in this directory");
    System.out.println("   • CLAUDE.md in project root (profiler section)");
    System.out.println("   • Profiler tool documentation (TOOLS.md)");
    System.out.println();
  }

  /**
   * Cleanup resources.
   */
  private void cleanup() {
    System.out.println();
    System.out.println("Cleaning up...");

    // Stop workloads
    if (computationWorkload.isRunning())
      computationWorkload.stop();
    if (allocationWorkload.isRunning())
      allocationWorkload.stop();
    if (concurrencyWorkload.isRunning())
      concurrencyWorkload.stop();
    if (ioWorkload.isRunning())
      ioWorkload.stop();

    // Stop server
    if (server != null) {
      server.stop();
    }

    System.out.println("Cleanup complete");
    System.out.println();
    System.out.println("Thank you for trying the Descartes Profiler!");
  }

  /**
   * Prints header banner.
   */
  private void printHeader() {
    System.out.println();
    System.out.println("=".repeat(80));
    System.out.println("DESCARTES PROFILER WORKFLOW EXAMPLE");
    System.out.println("=".repeat(80));
    System.out.println();
    System.out.println("This example demonstrates the complete profiler workflow:");
    System.out.println("   • Starting profiling sessions with different profile types");
    System.out.println("   • Generating realistic workloads (CPU, memory, locks, I/O)");
    System.out.println("   • Analyzing hotspots and call trees");
    System.out.println("   • Exporting interactive flame graphs");
    System.out.println("   • Using MCP tools for performance analysis");
    System.out.println();
  }

  /**
   * Pauses between demo sections (only in automated mode).
   */
  private void pause() throws InterruptedException {
    if (!interactiveMode) {
      System.out.println("Pausing 3 seconds before next demo...");
      System.out.println();
      Thread.sleep(3000);
    }
  }

  /**
   * Main entry point.
   */
  public static void main(String[] args) {
    // Check JDK version
    String javaVersion = System.getProperty("java.version");
    System.out.println("Java version: " + javaVersion);

    try {
      int majorVersion = Integer.parseInt(javaVersion.split("\\.")[0]);
      if (majorVersion < 11) {
        System.err.println("ERROR: JDK 11+ required for JFR support");
        System.err.println("   Current version: " + javaVersion);
        System.exit(1);
      }
    } catch (Exception e) {
      System.err.println("Warning: Could not parse Java version");
    }

    // Parse arguments
    boolean interactive = false;
    for (String arg : args) {
      if ("--interactive".equals(arg) || "-i".equals(arg)) {
        interactive = true;
      }
    }

    // Create and run example
    ProfilerWorkflowExample example = new ProfilerWorkflowExample(interactive);

    // Setup shutdown hook
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println();
      System.out.println("Shutdown hook triggered - cleaning up...");
      example.cleanup();
    }));

    // Run appropriate mode
    if (interactive) {
      example.runInteractive();
    } else {
      example.runAutomatedDemo();
    }
  }
}
