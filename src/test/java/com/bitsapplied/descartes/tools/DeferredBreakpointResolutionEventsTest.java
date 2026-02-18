package com.bitsapplied.descartes.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.DebuggeeLauncher;
import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.JDWPConnectionManager;
import com.bitsapplied.descartes.debugger.JDWPConnector;
import com.bitsapplied.descartes.debugger.integration.DebuggerNotificationBroadcaster;
import com.bitsapplied.descartes.debugger.models.DebugSessionConfig;
import com.bitsapplied.descartes.debugger.models.SessionState;
import com.bitsapplied.descartes.util.DebuggerEventQueues;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end integration test for deferred line breakpoint resolution surfaced
 * through debugger_events.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Isolated("Requires exclusive access to JDWP connection")
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class DeferredBreakpointResolutionEventsTest {
  private static final Logger logger = LoggerFactory.getLogger(DeferredBreakpointResolutionEventsTest.class);
  private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {
  };

  private static final String PROBE_CLASS = "com.bitsapplied.descartes.debugger.DeferredBreakpointProbe";
  private static final int PROBE_LINE = 12;

  private DebuggeeLauncher.DebuggeeHandle debuggee;
  private JDWPConnectionManager connectionManager;
  private DebuggerService debuggerService;
  private DebuggerExecutor debuggerExecutor;
  private DebuggerBreakpointsTool breakpointsTool;
  private DebuggerEventsTool eventsTool;
  private Map<String, Object> eventsContext;
  private AutoCloseable notificationRegistration;
  private ObjectMapper objectMapper;
  private Path triggerFile;

  @BeforeAll
  public void setupConnectionManager() throws Exception {
    JDWPConnector.resetCircuitBreaker();
    JDWPConnector.clearPortCache();

    triggerFile = Files.createTempFile("descartes-deferred-breakpoint-", ".trigger");
    Files.deleteIfExists(triggerFile);

    debuggee = DebuggeeLauncher.launchAndWait("com.bitsapplied.descartes.debugger.DeferredBreakpointDebuggee",
        List.of(triggerFile.toString()), 10_000);
    logger.info("Deferred debuggee launched on port {}", debuggee.getJdwpPort());

    connectionManager = new JDWPConnectionManager(debuggee.getJdwpPort());
  }

  @BeforeEach
  public void setUp() throws Exception {
    Files.deleteIfExists(triggerFile);

    debuggerService = new DebuggerService(connectionManager);
    debuggerExecutor = new DebuggerExecutor();
    breakpointsTool = new DebuggerBreakpointsTool(debuggerService, debuggerExecutor);
    eventsContext = new ConcurrentHashMap<>();
    eventsTool = new DebuggerEventsTool(eventsContext);
    objectMapper = new ObjectMapper();

    if (debuggerService.getState() != SessionState.READY) {
      DebugSessionConfig config = new DebugSessionConfig(10_000, false,
          new String[] { "java.*", "javax.*", "jdk.*", "sun.*" });
      debuggerService.start(config);
    }

    notificationRegistration = DebuggerNotificationBroadcaster.getInstance()
        .registerListener(notification -> DebuggerEventQueues.getOrCreate(eventsContext).addNotification(notification));

    debuggerService.getBreakpointManager().removeAllBreakpoints();
    eventsTool.executeAsync(Map.of("operation", "clear")).get();
  }

  @AfterEach
  public void tearDown() {
    try {
      if (notificationRegistration != null) {
        notificationRegistration.close();
      }
      DebuggerEventQueues.shutdown(eventsContext);
      Files.deleteIfExists(triggerFile);

      if (debuggerService != null && debuggerService.getState() != SessionState.CLOSED) {
        debuggerService.getBreakpointManager().removeAllBreakpoints();
        debuggerService.stop();
      }
    } catch (Exception e) {
      logger.warn("Error cleaning up deferred breakpoint test", e);
    }
  }

  @AfterAll
  public void shutdownConnectionManager() throws Exception {
    if (connectionManager != null) {
      connectionManager.shutdown();
    }
    if (debuggee != null) {
      debuggee.terminate();
    }
    if (triggerFile != null) {
      Files.deleteIfExists(triggerFile);
    }
  }

  @Test
  public void testDeferredBreakpointResolutionAppearsInDebuggerEvents() throws Exception {
    ToolResponse setResponse =
        breakpointsTool.executeAsync(Map.of("operation", "set", "class_name", PROBE_CLASS, "line_number", PROBE_LINE))
            .get();
    Map<String, Object> setResult = parseSuccess(setResponse);

    @SuppressWarnings("unchecked")
    Map<String, Object> breakpoint = (Map<String, Object>) setResult.get("breakpoint");
    long breakpointId = ((Number) breakpoint.get("id")).longValue();
    assertEquals("pending", breakpoint.get("state"));
    assertFalse((Boolean) breakpoint.get("verified"));
    assertEquals("class_not_loaded", breakpoint.get("pending_reason"));

    Files.writeString(triggerFile, "load");

    ToolResponse waitResponse = eventsTool.executeAsync(
        Map.of("operation", "wait", "types", List.of("debugger.breakpoint_resolved"), "timeout_ms", 15_000)).get();
    Map<String, Object> waitResult = parseSuccess(waitResponse);

    assertFalse((Boolean) waitResult.get("timed_out"), "Expected deferred breakpoint resolution event");

    @SuppressWarnings("unchecked")
    Map<String, Object> event = (Map<String, Object>) waitResult.get("event");
    assertNotNull(event);
    assertEquals("debugger.breakpoint_resolved", event.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) event.get("payload");
    assertEquals(breakpointId, ((Number) payload.get("breakpoint_id")).longValue());
    assertEquals(PROBE_CLASS, payload.get("class_name"));
    assertEquals(PROBE_LINE, ((Number) payload.get("line_number")).intValue());
    assertEquals("verified", payload.get("state"));
    assertEquals("success", setResult.get("status"));
  }

  private Map<String, Object> parseSuccess(ToolResponse response) throws Exception {
    if (!(response instanceof ToolResponse.Success success)) {
      throw new AssertionError("Expected success response but got: " + response);
    }
    return objectMapper.readValue(success.content(), MAP_TYPE_REF);
  }
}
