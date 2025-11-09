package com.bitsapplied.descartes.util;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.bitsapplied.descartes.settings.Setting;
import com.bitsapplied.descartes.settings.Settings;

/**
 * Utility for managing the shared executor used by blocking MCP tools.
 */
public final class ToolExecutors {

  public static final String CONTEXT_KEY = "descartes.tools.sharedExecutor";

  private ToolExecutors() {
  }

  /**
   * Obtains the shared executor stored in the MCP context, creating it if
   * necessary.
   *
   * @param context shared MCP context map
   * @return shared executor service
   */
  public static ExecutorService getSharedExecutor(Map<String, Object> context) {
    Objects.requireNonNull(context, "context");

    Object executor = context.compute(CONTEXT_KEY, (_, existing) -> {
      if (existing instanceof ExecutorService current && !current.isShutdown() && !current.isTerminated()) {
        return current;
      }
      return createExecutor();
    });

    return (ExecutorService) executor;
  }

  /**
   * Shuts down and removes the shared executor from the context.
   *
   * @param context shared MCP context map
   */
  public static void shutdownSharedExecutor(Map<String, Object> context) {
    if (context == null) {
      return;
    }

    // Get timeout from settings if available
    int timeoutSeconds = Setting.TOOLS_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS.defaultValue(Integer.class);
    Object settingsObj = context.get("settings");
    if (settingsObj instanceof Settings settings) {
      timeoutSeconds = settings.getInt(Setting.TOOLS_EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
    }

    Object existing = context.remove(CONTEXT_KEY);
    if (existing instanceof ExecutorService executor) {
      shutdownExecutor(executor, timeoutSeconds);
    }
  }

  private static ExecutorService createExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  private static void shutdownExecutor(ExecutorService executor, int timeoutSeconds) {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
