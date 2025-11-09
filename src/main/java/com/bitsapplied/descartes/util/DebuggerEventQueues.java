package com.bitsapplied.descartes.util;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/**
 * Stores a shared {@link DebuggerEventQueue} inside the MCP context so multiple
 * tools and the MCP server can coordinate debugger events without tightly
 * coupling components.
 */
public final class DebuggerEventQueues {

  private static final String CONTEXT_KEY = DebuggerEventQueue.class.getName();

  private DebuggerEventQueues() {
  }

  public static DebuggerEventQueue getOrCreate(Map<String, Object> context) {
    Objects.requireNonNull(context, "context");

    Object existing = context.get(CONTEXT_KEY);
    if (existing instanceof DebuggerEventQueue queue) {
      return queue;
    }

    DebuggerEventQueue newQueue = new DebuggerEventQueue();

    if (context instanceof ConcurrentMap<?, ?> concurrentMap) {
      @SuppressWarnings("unchecked")
      ConcurrentMap<String, Object> cmap = (ConcurrentMap<String, Object>) concurrentMap;
      Object prev = cmap.putIfAbsent(CONTEXT_KEY, newQueue);
      if (prev instanceof DebuggerEventQueue queue) {
        return queue;
      }
      return newQueue;
    }

    synchronized (context) {
      existing = context.get(CONTEXT_KEY);
      if (existing instanceof DebuggerEventQueue queue) {
        return queue;
      }
      context.put(CONTEXT_KEY, newQueue);
      return newQueue;
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

    if (removed instanceof DebuggerEventQueue queue) {
      queue.fetch(new DebuggerEventQueue.Filter(null, null), Integer.MAX_VALUE);
    }
  }
}
