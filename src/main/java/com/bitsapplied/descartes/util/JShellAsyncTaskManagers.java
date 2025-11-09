package com.bitsapplied.descartes.util;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/**
 * Context helper for sharing {@link JShellAsyncTaskManager} instances between
 * tools. Mirrors {@link JShellSessionManagers} so async evaluations reuse the
 * same infrastructure across MCP requests.
 */
public final class JShellAsyncTaskManagers {

  private static final String CONTEXT_KEY = JShellAsyncTaskManager.class.getName();

  private JShellAsyncTaskManagers() {
  }

  public static JShellAsyncTaskManager getOrCreate(Map<String, Object> context) {
    Objects.requireNonNull(context, "context");

    Object existing = context.get(CONTEXT_KEY);
    if (existing instanceof JShellAsyncTaskManager manager) {
      return manager;
    }

    JShellAsyncTaskManager newManager = new JShellAsyncTaskManager(context);

    if (context instanceof ConcurrentMap<?, ?> concurrentMap) {
      @SuppressWarnings("unchecked")
      ConcurrentMap<String, Object> cmap = (ConcurrentMap<String, Object>) concurrentMap;
      Object prev = cmap.putIfAbsent(CONTEXT_KEY, newManager);
      if (prev instanceof JShellAsyncTaskManager manager) {
        tryClose(newManager);
        return manager;
      }
      return newManager;
    }

    synchronized (context) {
      existing = context.get(CONTEXT_KEY);
      if (existing instanceof JShellAsyncTaskManager manager) {
        tryClose(newManager);
        return manager;
      }
      context.put(CONTEXT_KEY, newManager);
      return newManager;
    }
  }

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

    if (removed instanceof JShellAsyncTaskManager manager) {
      tryClose(manager);
    }
  }

  private static void tryClose(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception ignored) {
    }
  }
}
