package com.bitsapplied.descartes.debugger.integration;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight event bus that allows the debugger to broadcast notifications to
 * interested listeners (e.g., MCP clients).
 *
 * <p>
 * The broadcaster keeps listeners in a thread-safe collection and guarantees
 * that one listener throwing does not affect others.
 */
public final class DebuggerNotificationBroadcaster {
  private static final Logger logger = LoggerFactory.getLogger(DebuggerNotificationBroadcaster.class);
  private static final DebuggerNotificationBroadcaster INSTANCE = new DebuggerNotificationBroadcaster();

  private final CopyOnWriteArrayList<Consumer<MCPEventBridge.DebuggerNotification>> listeners = new CopyOnWriteArrayList<>();

  private DebuggerNotificationBroadcaster() {
  }

  /**
   * Gets the singleton broadcaster instance.
   */
  public static DebuggerNotificationBroadcaster getInstance() {
    return INSTANCE;
  }

  /**
   * Registers a listener for debugger notifications.
   *
   * @param listener the listener to register
   * @return a handle that removes the listener when closed
   */
  public AutoCloseable registerListener(Consumer<MCPEventBridge.DebuggerNotification> listener) {
    listeners.add(listener);

    return () -> listeners.remove(listener);
  }

  /**
   * Broadcasts a debugger notification to all registered listeners.
   *
   * @param notification the notification to broadcast
   */
  public void broadcast(MCPEventBridge.DebuggerNotification notification) {
    for (Consumer<MCPEventBridge.DebuggerNotification> listener : listeners) {
      try {
        listener.accept(notification);
      } catch (Exception e) {
        logger.error("Debugger notification listener failed", e);
      }
    }
  }
}
