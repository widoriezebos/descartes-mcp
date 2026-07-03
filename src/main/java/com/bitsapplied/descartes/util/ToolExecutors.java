package com.bitsapplied.descartes.util;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.bitsapplied.descartes.settings.Setting;
import com.bitsapplied.descartes.settings.Settings;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility for managing the shared executor used by blocking MCP tools.
 */
public final class ToolExecutors {

  private static final Logger logger = LogManager.getLogger(ToolExecutors.class);

  public static final String CONTEXT_KEY = "descartes.tools.sharedExecutor";
  public static final String FORCE_PLATFORM_THREADS_ENV = "DESCARTES_FORCE_PLATFORM_THREADS";

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

    Object executor = context.compute(CONTEXT_KEY, (_k, existing) -> {
      if (existing instanceof ExecutorService current && !current.isShutdown() && !current.isTerminated()) {
        return current;
      }
      return createExecutor(context);
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

  private static ExecutorService createExecutor(Map<String, Object> context) {
    Settings settings = settingsFrom(context);
    if (isPlatformThreadFallbackEnabled(settings)) {
      return createPlatformThreadExecutor(settings);
    }

    logger.info("Created virtual-thread tool executor");
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  private static boolean isPlatformThreadFallbackEnabled(Settings settings) {
    if (Boolean.parseBoolean(System.getenv(FORCE_PLATFORM_THREADS_ENV))) {
      logger.info("Platform threads forced via {} - virtual-thread tool executor disabled",
          FORCE_PLATFORM_THREADS_ENV);
      return true;
    }

    if (!settings.getBoolean(Setting.TOOLS_EXECUTOR_VIRTUAL_THREADS_ENABLED)) {
      logger.info("Platform threads enabled via {}=false", Setting.TOOLS_EXECUTOR_VIRTUAL_THREADS_ENABLED.key());
      return true;
    }

    return false;
  }

  private static ExecutorService createPlatformThreadExecutor(Settings settings) {
    int maxPoolSize = settings.getInt(Setting.TOOLS_EXECUTOR_PLATFORM_MAX_POOL_SIZE);
    int queueCapacity = settings.getInt(Setting.TOOLS_EXECUTOR_PLATFORM_QUEUE_CAPACITY);

    if (maxPoolSize < 1 || queueCapacity < 1) {
      logger.warn("Invalid platform tool executor settings detected (maxPoolSize={}, queueCapacity={}), "
          + "falling back to defaults", maxPoolSize, queueCapacity);
      maxPoolSize = Setting.TOOLS_EXECUTOR_PLATFORM_MAX_POOL_SIZE.defaultValue(Integer.class);
      queueCapacity = Setting.TOOLS_EXECUTOR_PLATFORM_QUEUE_CAPACITY.defaultValue(Integer.class);
    }

    ThreadFactory threadFactory = new ThreadFactory() {
      private final AtomicInteger threadNumber = new AtomicInteger(1);

      @Override
      public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "descartes-tool-" + threadNumber.getAndIncrement());
        thread.setDaemon(true);
        return thread;
      }
    };

    ThreadPoolExecutor executor = new ThreadPoolExecutor(maxPoolSize, maxPoolSize, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.CallerRunsPolicy());

    logger.info("Created bounded platform-thread tool executor: maxPoolSize={}, queueCapacity={}", maxPoolSize,
        queueCapacity);
    return executor;
  }

  private static Settings settingsFrom(Map<String, Object> context) {
    Object settingsObj = context.get("settings");
    if (settingsObj instanceof Settings settings) {
      return settings;
    }
    return new Settings();
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
