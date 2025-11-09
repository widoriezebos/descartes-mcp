package com.bitsapplied.descartes.util;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/**
 * Helper for sharing {@link JShellSessionManager} instances through the shared
 * MCP context map. Ensures a single manager is reused across tools and provides
 * a central shutdown hook.
 */
public final class JShellSessionManagers {

  private static final String CONTEXT_KEY = JShellSessionManager.class.getName();

  private JShellSessionManagers() {
  }

  /**
   * Obtain the shared {@link JShellSessionManager} for the supplied context,
   * creating it if necessary.
   * 
   * @param context the shared context
   * @return the shared session manager
   */
  public static JShellSessionManager getOrCreate(Map<String, Object> context) {
    Objects.requireNonNull(context, "context");

    Object existing = context.get(CONTEXT_KEY);
    if (existing instanceof JShellSessionManager manager) {
      return manager;
    }

    JShellSessionManager newManager = new JShellSessionManager(context);

    if (context instanceof ConcurrentMap<?, ?> concurrentMap) {
      @SuppressWarnings("unchecked")
      ConcurrentMap<String, Object> cmap = (ConcurrentMap<String, Object>) concurrentMap;
      Object prev = cmap.putIfAbsent(CONTEXT_KEY, newManager);
      if (prev instanceof JShellSessionManager manager) {
        // Another thread won the race - use existing instance
        tryClose(newManager);
        return manager;
      }
      return newManager;
    }

    synchronized (context) {
      existing = context.get(CONTEXT_KEY);
      if (existing instanceof JShellSessionManager manager) {
        tryClose(newManager);
        return manager;
      }
      context.put(CONTEXT_KEY, newManager);
      return newManager;
    }
  }

  /**
   * Remove and close the shared {@link JShellSessionManager} from the context if
   * present.
   * 
   * @param context the shared context
   */
  public static void shutdown(Map<String, Object> context) {
    if (context == null) {
      return;
    }

    Object removed;
    if (context instanceof ConcurrentMap<?, ?> concurrentMap) {
      @SuppressWarnings("unchecked")
      ConcurrentMap<String, Object> cmap = (ConcurrentMap<String, Object>) concurrentMap;
      removed = cmap.remove(CONTEXT_KEY);
    } else {
      synchronized (context) {
        removed = context.remove(CONTEXT_KEY);
      }
    }

    if (removed instanceof JShellSessionManager manager) {
      tryClose(manager);
    }
  }

  private static void tryClose(JShellSessionManager manager) {
    try {
      manager.close();
    } catch (Exception ignored) {
    }
  }
}
