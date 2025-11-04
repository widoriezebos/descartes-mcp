package com.bitsapplied.descartes.debugger.integration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.EventHub;
import com.bitsapplied.descartes.debugger.events.ErrorEvent;
import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.ThreadDeathEvent;
import com.sun.jdi.event.ThreadStartEvent;
import com.sun.jdi.event.VMDisconnectEvent;

import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Bridges debugger events to MCP notifications.
 *
 * <p>
 * This component subscribes to debugger events from the {@link EventHub} and
 * converts them to MCP-compatible notifications that can be sent to clients.
 *
 * <p>
 * Supported events:
 * <ul>
 * <li>Breakpoint hits</li>
 * <li>Step completions</li>
 * <li>Thread start/death</li>
 * <li>VM disconnect</li>
 * <li>Exception events</li>
 * </ul>
 *
 * <p>
 * Usage:
 * 
 * <pre>{@code
 * MCPEventBridge bridge = new MCPEventBridge(eventHub);
 * bridge.onNotification(notification -> {
 *   // Send notification to MCP clients
 *   mcpServer.sendNotification(notification);
 * });
 * bridge.start();
 * }</pre>
 */
public class MCPEventBridge {
  private static final Logger logger = LoggerFactory.getLogger(MCPEventBridge.class);

  private final EventHub eventHub;
  private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();
  private final List<Consumer<DebuggerNotification>> notificationHandlers = new CopyOnWriteArrayList<>();

  // Event statistics
  private final Map<String, Long> eventCounts = new ConcurrentHashMap<>();
  private volatile boolean started = false;

  /**
   * Creates an MCP event bridge.
   *
   * @param eventHub the debugger event hub
   */
  public MCPEventBridge(EventHub eventHub) {
    this.eventHub = eventHub;
  }

  /**
   * Starts bridging events.
   */
  public synchronized void start() {
    if (started) {
      logger.warn("MCPEventBridge already started");
      return;
    }

    logger.info("Starting MCP event bridge...");

    // Subscribe to error events from event processing
    subscriptions.add(eventHub.eventsOfType(ErrorEvent.class).subscribe(this::handleErrorEvent, this::handleError));

    // Subscribe to breakpoint events
    subscriptions
        .add(eventHub.jdiEventsOfType(BreakpointEvent.class).subscribe(this::handleBreakpointEvent, this::handleError));

    // Subscribe to step events
    subscriptions.add(eventHub.jdiEventsOfType(StepEvent.class).subscribe(this::handleStepEvent, this::handleError));

    // Subscribe to method entry events
    subscriptions.add(
        eventHub.jdiEventsOfType(MethodEntryEvent.class).subscribe(this::handleMethodEntryEvent, this::handleError));

    // Subscribe to method exit events
    subscriptions
        .add(eventHub.jdiEventsOfType(MethodExitEvent.class).subscribe(this::handleMethodExitEvent, this::handleError));

    // Subscribe to thread start events
    subscriptions.add(
        eventHub.jdiEventsOfType(ThreadStartEvent.class).subscribe(this::handleThreadStartEvent, this::handleError));

    // Subscribe to thread death events
    subscriptions.add(
        eventHub.jdiEventsOfType(ThreadDeathEvent.class).subscribe(this::handleThreadDeathEvent, this::handleError));

    // Subscribe to exception events
    subscriptions
        .add(eventHub.jdiEventsOfType(ExceptionEvent.class).subscribe(this::handleExceptionEvent, this::handleError));

    // Subscribe to VM disconnect events
    subscriptions.add(
        eventHub.jdiEventsOfType(VMDisconnectEvent.class).subscribe(this::handleVMDisconnectEvent, this::handleError));

    started = true;
    logger.info("MCP event bridge started");
  }

  /**
   * Stops bridging events.
   */
  public synchronized void stop() {
    if (!started) {
      return;
    }

    logger.info("Stopping MCP event bridge...");

    subscriptions.forEach(Disposable::dispose);
    subscriptions.clear();

    started = false;
    logger.info("MCP event bridge stopped");
  }

  /**
   * Registers a notification handler.
   *
   * @param handler the notification handler
   */
  public void onNotification(Consumer<DebuggerNotification> handler) {
    notificationHandlers.add(handler);
  }

  /**
   * Gets event statistics.
   *
   * @return map of event types to counts
   */
  public Map<String, Long> getEventStatistics() {
    return Map.copyOf(eventCounts);
  }

  /**
   * Checks if the bridge is started.
   *
   * @return true if started
   */
  public boolean isStarted() {
    return started;
  }

  // ========== Event Handlers ==========

  private void handleBreakpointEvent(BreakpointEvent event) {
    incrementEventCount("breakpoint");

    ThreadReference thread = event.thread();
    Location location = event.location();

    DebuggerNotification notification = new DebuggerNotification("debugger.breakpoint_hit",
        Map.of("thread_id", thread.uniqueID(), "thread_name", thread.name(), "class", location.declaringType().name(),
            "method", location.method().name(), "line", location.lineNumber(), "source_path", getSourcePath(location)));

    emitNotification(notification);
    logger.debug("Breakpoint hit: {} at {}:{}", thread.name(), getSourcePath(location), location.lineNumber());
  }

  private void handleStepEvent(StepEvent event) {
    incrementEventCount("step");

    ThreadReference thread = event.thread();
    Location location = event.location();

    DebuggerNotification notification = new DebuggerNotification("debugger.step_complete",
        Map.of("thread_id", thread.uniqueID(), "thread_name", thread.name(), "class", location.declaringType().name(),
            "method", location.method().name(), "line", location.lineNumber(), "source_path", getSourcePath(location)));

    emitNotification(notification);
    logger.debug("Step complete: {} at {}:{}", thread.name(), getSourcePath(location), location.lineNumber());
  }

  private void handleMethodEntryEvent(MethodEntryEvent event) {
    incrementEventCount("method_entry");

    ThreadReference thread = event.thread();
    Location location = event.location();

    DebuggerNotification notification = new DebuggerNotification("debugger.method_entry",
        Map.of("thread_id", thread.uniqueID(), "thread_name", thread.name(), "class", location.declaringType().name(),
            "method", location.method().name()));

    emitNotification(notification);
  }

  private void handleMethodExitEvent(MethodExitEvent event) {
    incrementEventCount("method_exit");

    ThreadReference thread = event.thread();
    Location location = event.location();

    DebuggerNotification notification = new DebuggerNotification("debugger.method_exit",
        Map.of("thread_id", thread.uniqueID(), "thread_name", thread.name(), "class", location.declaringType().name(),
            "method", location.method().name()));

    emitNotification(notification);
  }

  private void handleThreadStartEvent(ThreadStartEvent event) {
    incrementEventCount("thread_start");

    ThreadReference thread = event.thread();

    DebuggerNotification notification = new DebuggerNotification("debugger.thread_start",
        Map.of("thread_id", thread.uniqueID(), "thread_name", thread.name()));

    emitNotification(notification);
    logger.debug("Thread started: {}", thread.name());
  }

  private void handleThreadDeathEvent(ThreadDeathEvent event) {
    incrementEventCount("thread_death");

    ThreadReference thread = event.thread();

    DebuggerNotification notification = new DebuggerNotification("debugger.thread_death",
        Map.of("thread_id", thread.uniqueID(), "thread_name", thread.name()));

    emitNotification(notification);
    logger.debug("Thread ended: {}", thread.name());
  }

  private void handleExceptionEvent(ExceptionEvent event) {
    incrementEventCount("exception");

    ThreadReference thread = event.thread();
    Location location = event.location();

    DebuggerNotification notification = new DebuggerNotification("debugger.exception",
        Map.of("thread_id", thread.uniqueID(), "thread_name", thread.name(), "exception_type",
            event.exception().referenceType().name(), "class",
            location != null ? location.declaringType().name() : "unknown", "method",
            location != null ? location.method().name() : "unknown", "line",
            location != null ? location.lineNumber() : -1));

    emitNotification(notification);
    logger.debug("Exception: {} in thread {}", event.exception().referenceType().name(), thread.name());
  }

  private void handleVMDisconnectEvent(VMDisconnectEvent event) {
    incrementEventCount("vm_disconnect");

    DebuggerNotification notification = new DebuggerNotification("debugger.vm_disconnect",
        Map.of("timestamp", System.currentTimeMillis()));

    emitNotification(notification);
    logger.info("VM disconnected");
  }

  /**
   * Handles error events from the EventHub.
   *
   * <p>
   * Error events represent recoverable errors in event processing. Depending on
   * severity, we may emit notifications to MCP clients to alert them of debugger
   * issues.
   *
   * @param errorEvent the error event
   */
  private void handleErrorEvent(ErrorEvent errorEvent) {
    incrementEventCount("error");

    logger.warn("Debug error event received: {} (severity: {})", errorEvent.context(), errorEvent.severity());

    // Emit notification for non-recoverable errors
    // Clients should be aware of WARNING and CRITICAL errors
    if (errorEvent.severity() != ErrorEvent.Severity.RECOVERABLE) {
      DebuggerNotification notification = new DebuggerNotification("debugger.error",
          Map.of("message", errorEvent.context(), "severity", errorEvent.severity().name(), "exception_type",
              errorEvent.getExceptionType(), "exception_message", errorEvent.getMessage(), "timestamp",
              errorEvent.timestamp()));

      emitNotification(notification);
    }
  }

  private void handleError(Throwable error) {
    logger.error("Error in event bridge subscription", error);
  }

  // ========== Helper Methods ==========

  private void emitNotification(DebuggerNotification notification) {
    for (Consumer<DebuggerNotification> handler : notificationHandlers) {
      try {
        handler.accept(notification);
      } catch (Exception e) {
        logger.error("Error in notification handler", e);
      }
    }
  }

  private void incrementEventCount(String eventType) {
    eventCounts.merge(eventType, 1L, Long::sum);
  }

  private String getSourcePath(Location location) {
    try {
      return location.sourcePath();
    } catch (Exception e) {
      // Source path may not be available, try source name
      try {
        return location.sourceName();
      } catch (Exception ex) {
        // Source name may also not be available
        return "unknown";
      }
    }
  }

  // ========== Inner Classes ==========

  /**
   * Represents a debugger notification for MCP clients.
   *
   * @param type    the notification type (e.g., "debugger.breakpoint_hit")
   * @param payload the notification payload
   */
  public record DebuggerNotification(String type, Map<String, Object> payload) {
    /**
     * Converts to MCP notification format.
     *
     * @return MCP notification map
     */
    public Map<String, Object> toMCPNotification() {
      return Map.of("method", "notifications/debugger", "params",
          Map.of("type", type, "data", payload, "timestamp", System.currentTimeMillis()));
    }
  }
}
