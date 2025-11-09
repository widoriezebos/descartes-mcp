package com.bitsapplied.descartes.util;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone exception buffer for tracking application exceptions. This
 * provides the exception tracking functionality that was previously part of
 * InMemoryAppender, but is now separated as a distinct concern.
 */
public class ExceptionBuffer {
  private static final ExceptionBuffer INSTANCE = new ExceptionBuffer();

  private static final int DEFAULT_MAX_EXCEPTIONS = 50;
  private final int maxExceptions;
  private final LinkedList<ExceptionRecord> exceptionBuffer;
  private final Map<String, AtomicInteger> exceptionStats;

  /**
   * Exception record with timestamp and details.
   */
  public static class ExceptionRecord {
    private final Instant timestamp;
    private final String exceptionType;
    private final String message;
    private final String stackTrace;

    public ExceptionRecord(Instant timestamp, String exceptionType, String message, String stackTrace) {
      this.timestamp = timestamp;
      this.exceptionType = exceptionType;
      this.message = message;
      this.stackTrace = stackTrace;
    }

    public Instant getTimestamp() {
      return timestamp;
    }

    public String getExceptionType() {
      return exceptionType;
    }

    public String getMessage() {
      return message;
    }

    public String getStackTrace() {
      return stackTrace;
    }
  }

  private ExceptionBuffer() {
    this(DEFAULT_MAX_EXCEPTIONS);
  }

  private ExceptionBuffer(int maxExceptions) {
    this.maxExceptions = maxExceptions;
    this.exceptionBuffer = new LinkedList<>();
    this.exceptionStats = new ConcurrentHashMap<>();
  }

  public static ExceptionBuffer getInstance() {
    return INSTANCE;
  }

  /**
   * Add an exception to the buffer.
   */
  public synchronized void addException(String exceptionType, String message, String stackTrace) {
    ExceptionRecord record = new ExceptionRecord(Instant.now(), exceptionType, message, stackTrace);

    exceptionBuffer.addLast(record);

    // Maintain size limit
    while (exceptionBuffer.size() > maxExceptions) {
      exceptionBuffer.removeFirst();
    }

    // Update statistics
    exceptionStats.computeIfAbsent(exceptionType, _ -> new AtomicInteger(0)).incrementAndGet();
  }

  /**
   * Get recent exceptions (up to maxCount).
   */
  public synchronized List<ExceptionRecord> getRecentExceptions(int maxCount) {
    int count = Math.min(maxCount, exceptionBuffer.size());
    if (count == 0) {
      return List.of();
    }

    List<ExceptionRecord> result = new ArrayList<>(count);
    var it = exceptionBuffer.descendingIterator();
    while (it.hasNext() && result.size() < count) {
      result.add(it.next());
    }
    return result;
  }

  /**
   * Get recent exceptions as formatted strings (for backward compatibility).
   */
  public synchronized List<String> getLastExceptions(int maxCount) {
    List<ExceptionRecord> records = getRecentExceptions(maxCount);
    return records.stream().map(this::formatException).toList();
  }

  /**
   * Get the most recent exception.
   */
  public synchronized ExceptionRecord getLastException() {
    return exceptionBuffer.isEmpty() ? null : exceptionBuffer.getLast();
  }

  /**
   * Get the most recent exception as a formatted string (for backward
   * compatibility).
   */
  public synchronized String getLastExceptionString() {
    ExceptionRecord record = getLastException();
    return record != null ? formatException(record) : null;
  }

  /**
   * Get all exceptions as formatted strings (for backward compatibility).
   */
  public synchronized List<String> getExceptionBuffer() {
    return exceptionBuffer.stream().map(this::formatException).toList();
  }

  /**
   * Format an exception record as a string.
   */
  private String formatException(ExceptionRecord record) {
    return String.format("%s %s: %s\n%s", record.timestamp, record.exceptionType,
        record.message != null ? record.message : "", record.stackTrace);
  }

  /**
   * Get max buffer size.
   */
  public int getMaxExceptionBufferSize() {
    return maxExceptions;
  }

  /**
   * Get truncate back to size (same as max for this simplified version).
   */
  public int getTruncateExceptionBackTo() {
    return maxExceptions;
  }

  /**
   * Clear the exception buffer.
   */
  public synchronized void clearExceptionBuffer() {
    clear();
  }

  /**
   * Get exception statistics (counts by type).
   */
  public synchronized Map<String, Integer> getExceptionStats() {
    Map<String, Integer> stats = new ConcurrentHashMap<>();
    exceptionStats.forEach((type, count) -> stats.put(type, count.get()));
    return stats;
  }

  /**
   * Clear all exceptions and statistics.
   */
  public synchronized void clear() {
    exceptionBuffer.clear();
    exceptionStats.clear();
  }

  /**
   * Get the current buffer size.
   */
  public synchronized int size() {
    return exceptionBuffer.size();
  }
}
