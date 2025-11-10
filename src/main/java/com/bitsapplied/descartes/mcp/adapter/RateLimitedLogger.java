package com.bitsapplied.descartes.mcp.adapter;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class RateLimitedLogger {
  private static final String PREFIX = "[MCP-TCP-Adapter]";
  private static final String PREFIX_DEBUG = "[MCP-TCP-Adapter DEBUG]";
  private static final String PREFIX_ERROR = "[MCP-TCP-Adapter ERROR]";

  private final AdapterConfig config;
  private final ConcurrentHashMap<String, LogBucket> logCounts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicInteger> suppressedCounts = new ConcurrentHashMap<>();
  private final DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;
  private final ScheduledFuture<?> cleanupTask;

  RateLimitedLogger(AdapterConfig config, ScheduledExecutorService scheduler) {
    this.config = config;
    this.cleanupTask = scheduler.scheduleAtFixedRate(this::performMaintenance, config.logRateLimitWindowMs,
        config.logRateLimitWindowMs, TimeUnit.MILLISECONDS);
  }

  void debug(String message) {
    if (!config.debug) {
      return;
    }
    if (!shouldRateLimit("debug", message)) {
      emit(PREFIX_DEBUG, message);
    }
  }

  void info(String message) {
    if (!shouldRateLimit("info", message)) {
      emit(PREFIX, message);
    }
  }

  void error(String message) {
    emit(PREFIX_ERROR, message);
  }

  void error(String message, Throwable throwable) {
    emit(PREFIX_ERROR, message);
    if (throwable != null) {
      emit(PREFIX_ERROR, stackTraceToString(throwable));
    }
  }

  void shutdown() {
    cleanupTask.cancel(false);
    performMaintenance();
  }

  private void performMaintenance() {
    try {
      long now = System.currentTimeMillis();
      for (Map.Entry<String, LogBucket> entry : logCounts.entrySet()) {
        LogBucket bucket = entry.getValue();
        if (now - bucket.firstTimestamp > config.logRateLimitWindowMs) {
          logCounts.remove(entry.getKey(), bucket);
          suppressedCounts.remove(entry.getKey());
        }
      }
      String summary = buildSuppressedSummary();
      if (summary != null) {
        emit(PREFIX, summary);
      }
    } catch (Throwable t) {
      emit(PREFIX_ERROR, "Logger maintenance failure: " + t.getMessage());
    }
  }

  private String buildSuppressedSummary() {
    if (suppressedCounts.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, AtomicInteger> entry : suppressedCounts.entrySet()) {
      int count = entry.getValue().getAndSet(0);
      if (count <= 0) {
        continue;
      }
      String key = entry.getKey();
      int idx = key.indexOf(':');
      String category = idx >= 0 ? key.substring(0, idx) : key;
      String message = idx >= 0 ? key.substring(idx + 1) : "";
      parts.add(String.format(Locale.ROOT, "%d %s messages: \"%s\"", count, category, message));
    }
    if (parts.isEmpty()) {
      return null;
    }
    return "Log Summary - Suppressed: " + String.join(", ", parts);
  }

  private boolean shouldRateLimit(String category, String message) {
    String key = category + ":" + message;
    long now = System.currentTimeMillis();
    LogBucket bucket = logCounts.compute(key, (_, existing) -> {
      if (existing == null || now - existing.firstTimestamp > config.logRateLimitWindowMs) {
        return new LogBucket(now, 1);
      }
      existing.count++;
      return existing;
    });
    if (bucket.count > config.logRateLimitMax) {
      suppressedCounts.computeIfAbsent(key, _ -> new AtomicInteger()).incrementAndGet();
      return true;
    }
    return false;
  }

  private void emit(String prefix, String message) {
    System.err.printf(Locale.ROOT, "%s %s - %s%n", prefix, formatter.format(Instant.now()), message);
  }

  private static String stackTraceToString(Throwable throwable) {
    StringBuilder builder = new StringBuilder();
    builder.append(throwable);
    for (StackTraceElement element : throwable.getStackTrace()) {
      builder.append(System.lineSeparator()).append("\tat ").append(element);
    }
    Throwable cause = throwable.getCause();
    while (cause != null) {
      builder.append(System.lineSeparator()).append("Caused by: ").append(cause);
      for (StackTraceElement element : cause.getStackTrace()) {
        builder.append(System.lineSeparator()).append("\tat ").append(element);
      }
      cause = cause.getCause();
    }
    return builder.toString();
  }

  private static final class LogBucket {
    final long firstTimestamp;
    int count;

    LogBucket(long firstTimestamp, int count) {
      this.firstTimestamp = firstTimestamp;
      this.count = count;
    }
  }
}
