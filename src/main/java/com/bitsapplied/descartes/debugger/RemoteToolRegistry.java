package com.bitsapplied.descartes.debugger;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.tools.DebuggerBreakpointsTool;
import com.bitsapplied.descartes.tools.DebuggerEvaluateTool;
import com.bitsapplied.descartes.tools.DebuggerEventsTool;
import com.bitsapplied.descartes.tools.DebuggerSessionTool;
import com.bitsapplied.descartes.tools.DebuggerStackTraceTool;
import com.bitsapplied.descartes.tools.DebuggerStepTool;
import com.bitsapplied.descartes.tools.DebuggerThreadsTool;
import com.bitsapplied.descartes.tools.DebuggerVariablesTool;
import com.bitsapplied.descartes.tools.DebuggerWatchTool;
import com.bitsapplied.descartes.tools.ObjectInspectorTool;
import com.bitsapplied.descartes.tools.ThreadAnalyzerTool;

/**
 * Registry for JDWP-compatible tools in remote debug proxy mode.
 *
 * <p>
 * Only registers tools that work via JDWP (Java Debug Wire Protocol):
 * <ul>
 * <li><b>Debugger Tools</b> (9 tools): All debugger_* tools work via JDI API
 * over JDWP
 * <li><b>Thread Analyzer</b>: Works via JDI ThreadReference API
 * <li><b>Object Inspector</b>: Works via JDI ObjectReference API
 * </ul>
 *
 * <p>
 * Tools <b>NOT</b> registered (require in-process access):
 * <ul>
 * <li>JShell REPL tools (jshell_repl, jshell_async, jshell_session_manager)
 * <li>Hot reload (hot_reload_classes) - requires Java agent
 * <li>Monitoring tools (system_monitoring, memory_analyzer) - require JMX
 * <li>Logging tools (log_file_discovery, log_file_search) - available when
 * Log4j2 is configured
 * <li>Profiler tools (profiler_*) - require JFR access
 * </ul>
 *
 * <p>
 * See doc/MCPRemoteDebugProxy.md for comprehensive tool compatibility matrix.
 */
public class RemoteToolRegistry {

  private static final Logger logger = LoggerFactory.getLogger(RemoteToolRegistry.class);

  /**
   * Registers all JDWP-compatible tools on the MCP server.
   *
   * @param server           MCP server to register tools on
   * @param context          application context map
   * @param debuggerService  shared debugger service instance
   * @param debuggerExecutor shared debugger executor (single-threaded for JDI
   *                         safety)
   */
  public static void registerTools(MCPServer server, Map<String, Object> context, DebuggerService debuggerService,
      DebuggerExecutor debuggerExecutor) {

    logger.info("Registering JDWP-compatible tools for remote debug proxy mode...");

    int toolCount = 0;

    // ===== Debugger Tools (9 tools) =====
    // All debugger tools work via JDI API over JDWP socket

    server.registerTool(new DebuggerSessionTool(debuggerService, debuggerExecutor));
    toolCount++;
    logger.debug("Registered: debugger_session");

    server.registerTool(new DebuggerBreakpointsTool(debuggerService, debuggerExecutor));
    toolCount++;
    logger.debug("Registered: debugger_breakpoints");

    server.registerTool(new DebuggerStepTool(debuggerService, debuggerExecutor));
    toolCount++;
    logger.debug("Registered: debugger_step");

    server.registerTool(new DebuggerThreadsTool(debuggerService, debuggerExecutor));
    toolCount++;
    logger.debug("Registered: debugger_threads");

    server.registerTool(new DebuggerVariablesTool(debuggerService, debuggerExecutor));
    toolCount++;
    logger.debug("Registered: debugger_variables");

    server.registerTool(new DebuggerStackTraceTool(debuggerService, debuggerExecutor));
    toolCount++;
    logger.debug("Registered: debugger_stacktrace");

    server.registerTool(new DebuggerWatchTool(debuggerService, debuggerExecutor));
    toolCount++;
    logger.debug("Registered: debugger_watch");

    server.registerTool(new DebuggerEvaluateTool(debuggerService, debuggerExecutor));
    toolCount++;
    logger.debug("Registered: debugger_evaluate");

    server.registerTool(new DebuggerEventsTool(context));
    toolCount++;
    logger.debug("Registered: debugger_events");

    // ===== Analysis Tools (2 tools) =====
    // These work via JDI ThreadReference and ObjectReference APIs

    server.registerTool(new ThreadAnalyzerTool(context));
    toolCount++;
    logger.debug("Registered: thread_analyzer");

    server.registerTool(new ObjectInspectorTool(context));
    toolCount++;
    logger.debug("Registered: object_inspector");

    logger.info("Successfully registered {} JDWP-compatible tools", toolCount);

    // Log tools NOT registered (for clarity)
    logger.info("Tools NOT registered (require in-process access):");
    logger.info("  - jshell_repl, jshell_async (require JShell instance in target)");
    logger.info("  - hot_reload_classes (requires Java agent in target)");
    logger.info("  - system_monitoring, memory_analyzer (require JMX/local access)");
    logger.info("  - Note: Logging tools are available when Log4j2 is configured");
    logger.info("  - profiler_* tools (require JFR access)");
  }

  /**
   * Returns count of tools that will be registered.
   */
  public static int getToolCount() {
    return 11; // 9 debugger + 2 analysis tools
  }
}
