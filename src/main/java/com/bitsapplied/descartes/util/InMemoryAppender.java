package com.bitsapplied.descartes.util;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * A custom in‑memory Log4j2 appender that buffers formatted log lines.
 * 
 * <p>
 * The appender accepts only log events that meet two criteria:
 * <ol>
 * <li>The event’s level is at least the configured threshold.</li>
 * <li>The logger name begins with any of the configured prefixes. The filters
 * are maintained as a dynamic list. An event is accepted if its logger name
 * starts with any filter string in that list.</li>
 * </ol>
 * 
 * <p>
 * When the number of buffered lines reaches {@code maxBufferSize}, the buffer
 * is trimmed down to {@code truncateBackTo} lines (keeping the most recent
 * lines) to make room for new messages.
 * 
 * <p>
 * You can also change the threshold level dynamically at runtime.
 * 
 * <p>
 * <b>Listener Mechanism:</b> You can add listeners (via
 * {@code addLogListener(...)}) that will be notified when a new log message is
 * appended. The add method returns a snapshot of the current log buffer in a
 * synchronized manner so that no messages are missed between the current logs
 * and the start of listening.
 */
@Plugin(name = "InMemoryAppender", category = "Core", elementType = "appender", printObject = true)
public class InMemoryAppender extends AbstractAppender {

  // Buffer to hold formatted log messages.
  private final List<String> buffer = new ArrayList<>();
  // Buffer to hold formatted exception messages.
  private final List<String> exceptionBuffer = new ArrayList<>();
  // Maximum number of lines allowed in the buffer.
  private volatile int maxBufferSize;
  // When truncating, keep this many (most recent) lines.
  private volatile int truncateBackTo;
  // Maximum number of exceptions allowed in the exception buffer.
  private volatile int maxExceptionBufferSize;
  // When truncating exceptions, keep this many (most recent) exceptions.
  private volatile int truncateExceptionBackTo;
  // The minimum logging level for events to be buffered (can be changed at
  // runtime).
  private final CopyOnWriteArrayList<String> loggerFilters;
  // A thread-safe list of log listeners.
  private final CopyOnWriteArrayList<LogListener> listeners = new CopyOnWriteArrayList<>();

  /**
   * Interface for receiving notifications when a new log message is received.
   */
  public static interface LogListener {
    /**
     * Called when a new log message is appended.
     *
     * @param message the formatted log message.
     */
    void onNewLog(String message);
  }

  /**
   * Creates a new InMemoryAppender.
   *
   * @param name             the appender name
   * @param filter           the filter for the appender
   * @param layout           the layout for formatting log events
   * @param ignoreExceptions whether to ignore exceptions during logging
   * @param maxBufferSize    maximum number of log lines to buffer
   * @param truncateBackTo   when the buffer is full, the number of most recent
   *                         lines to keep
   * @param level            the minimum logging level to accept
   * @param loggerFilter     a comma‑separated list of logger name prefixes to
   *                         initialize the filters
   */
  protected InMemoryAppender(String name, Filter filter, Layout<? extends Serializable> layout,
      boolean ignoreExceptions, int maxBufferSize, int truncateBackTo, String loggerFilter) {
    super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
    this.maxBufferSize = maxBufferSize;
    this.truncateBackTo = truncateBackTo;
    // Set default exception buffer sizes (can be made configurable later)
    this.maxExceptionBufferSize = 50;
    this.truncateExceptionBackTo = 40;
    // Initialize the filters from a comma-separated list, then store them in a
    // thread-safe list.
    this.loggerFilters = new CopyOnWriteArrayList<>(parseLoggerFilters(loggerFilter));
  }

  @PluginFactory
  public static InMemoryAppender createAppender(@PluginAttribute("name") String name,
      @PluginAttribute(value = "maxBufferSize", defaultInt = 100) int maxBufferSize,
      @PluginAttribute(value = "truncateBackTo", defaultInt = 50) int truncateBackTo,
      @PluginAttribute(value = "level", defaultString = "INFO") String levelStr,
      // This attribute is a comma‑separated list of logger name prefixes.
      @PluginAttribute(value = "loggerFilter", defaultString = "com.bitsapplied.morpheus.core.") String loggerFilter,
      @PluginElement("Layout") Layout<? extends Serializable> layout, @PluginElement("Filter") final Filter filter) {

    if (name == null) {
      LOGGER.error("No name provided for InMemoryAppender");
      return null;
    }
    if (layout == null) {
      layout = PatternLayout.createDefaultLayout();
    }
    return new InMemoryAppender(name, filter, layout, true, maxBufferSize, truncateBackTo, loggerFilter);
  }

  @Override
  public void append(LogEvent event) {
    // Only buffer events from loggers that match at least one filter.
    String loggerName = event.getLoggerName();
    if (loggerName == null || !matchesAnyFilter(loggerName)) {
      return;
    }
    // Format the log event using the provided layout.
    byte[] bytes = getLayout().toByteArray(event);
    String formattedMessage = new String(bytes, StandardCharsets.UTF_8);

    // Synchronize access to the buffer.
    synchronized (buffer) {
      if (buffer.size() >= maxBufferSize) {
        // Truncate the buffer: remove oldest lines until its size is truncateBackTo.
        while (buffer.size() > truncateBackTo) {
          buffer.remove(0);
        }
      }
      buffer.add(formattedMessage);
    }

    // Check if this event contains an exception and buffer it separately
    Throwable throwable = event.getThrown();
    if (throwable != null) {
      synchronized (exceptionBuffer) {
        if (exceptionBuffer.size() >= maxExceptionBufferSize) {
          // Truncate the exception buffer: remove oldest exceptions until its size is
          // truncateExceptionBackTo.
          while (exceptionBuffer.size() > truncateExceptionBackTo) {
            exceptionBuffer.remove(0);
          }
        }
        // Include the formatted message with the stack trace
        StringBuilder exceptionEntry = new StringBuilder(formattedMessage);
        // Add the full stack trace
        exceptionEntry.append(formatThrowable(throwable));
        exceptionBuffer.add(exceptionEntry.toString());
      }
    }

    // Notify all registered listeners of the new log message.
    for (LogListener listener : listeners) {
      try {
        listener.onNewLog(formattedMessage);
      } catch (Exception e) {
        // Optionally handle or log the exception.
        // Avoid throwing exceptions from within the appender.
      }
    }
  }

  /**
   * Checks if the given logger name matches any of the current logger filters.
   *
   * @param loggerName the logger name to check
   * @return true if the logger name starts with any of the filters, false
   *         otherwise
   */
  private boolean matchesAnyFilter(String loggerName) {
    for (String prefix : loggerFilters) {
      if (loggerName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Parses a comma‑separated list of logger filters into a list of trimmed
   * strings.
   *
   * @param loggerFilter a comma‑separated list of logger name prefixes
   * @return a list of logger name prefixes
   */
  private static List<String> parseLoggerFilters(String loggerFilter) {
    List<String> filters = new ArrayList<>();
    if (loggerFilter != null && !loggerFilter.trim().isEmpty()) {
      for (String filter : loggerFilter.split(",")) {
        String trimmed = filter.trim();
        if (!trimmed.isEmpty()) {
          filters.add(trimmed);
        }
      }
    }
    return filters;
  }

  /**
   * Returns a copy of the current log buffer.
   *
   * @return a list of formatted log lines.
   */
  public List<String> getLogBuffer() {
    synchronized (buffer) {
      return new ArrayList<>(buffer);
    }
  }

  /**
   * Dynamically updates the maximum buffer size.
   *
   * @param newMaxBufferSize the new maximum number of log lines to buffer.
   */
  public void setMaxBufferSize(int newMaxBufferSize) {
    this.maxBufferSize = newMaxBufferSize;
  }

  /**
   * Returns the current maximum buffer size.
   *
   * @return the maximum number of log lines that can be buffered.
   */
  public int getMaxBufferSize() {
    return this.maxBufferSize;
  }

  /**
   * Dynamically updates the truncate back-to value.
   *
   * @param newTruncateBackTo the new number of most recent log lines to keep when
   *                          trimming the buffer.
   */
  public void setTruncateBackTo(int newTruncateBackTo) {
    this.truncateBackTo = newTruncateBackTo;
  }

  /**
   * Returns the current truncate back-to value.
   *
   * @return the number of most recent log lines kept when trimming the buffer.
   */
  public int getTruncateBackTo() {
    return this.truncateBackTo;
  }

  /**
   * Adds a logger filter to the appender. The new filter will be added to the
   * current list.
   *
   * @param filter the logger name prefix to add
   */
  public void addLoggerFilter(String filter) {
    if (filter != null && !filter.trim().isEmpty()) {
      loggerFilters.add(filter.trim());
    }
  }

  /**
   * Removes a logger filter from the appender.
   *
   * @param filter the logger name prefix to remove
   * @return true if the filter was found and removed, false otherwise
   */
  public boolean removeLoggerFilter(String filter) {
    return loggerFilters.remove(filter);
  }

  /**
   * Returns an unmodifiable copy of the current logger filters.
   *
   * @return a list of logger filters.
   */
  public List<String> getLoggerFilters() {
    return new ArrayList<>(loggerFilters);
  }

  /**
   * Adds a log listener to this appender.
   * 
   * <p>
   * This method is synchronized on the log buffer so that the returned snapshot
   * of buffered log lines includes all messages up to the point of listener
   * registration.
   *
   * @param listener the listener to add
   * @return a snapshot of the current log buffer
   * @throws IllegalArgumentException if listener is null
   */
  public List<String> addLogListener(LogListener listener) {
    if (listener == null) {
      throw new IllegalArgumentException("listener cannot be null");
    }
    synchronized (buffer) {
      listeners.add(listener);
      return new ArrayList<>(buffer);
    }
  }

  /**
   * Removes a log listener from this appender.
   *
   * @param listener the listener to remove
   * @return true if the listener was registered and removed, false otherwise
   */
  public boolean removeLogListener(LogListener listener) {
    return listeners.remove(listener);
  }

  /**
   * Formats a Throwable into a string with its stack trace.
   *
   * @param throwable the exception to format
   * @return the formatted stack trace as a string
   */
  private String formatThrowable(Throwable throwable) {
    StringBuilder sb = new StringBuilder();
    sb.append(throwable.getClass().getName()).append(": ").append(throwable.getMessage()).append("\n");
    for (StackTraceElement element : throwable.getStackTrace()) {
      sb.append("\tat ").append(element.toString()).append("\n");
    }
    // Handle nested exceptions
    Throwable cause = throwable.getCause();
    if (cause != null) {
      sb.append("Caused by: ");
      sb.append(formatThrowable(cause));
    }
    return sb.toString();
  }

  /**
   * Returns a copy of the exception buffer.
   *
   * @return a list of formatted exception entries with stack traces
   */
  public List<String> getExceptionBuffer() {
    synchronized (exceptionBuffer) {
      return new ArrayList<>(exceptionBuffer);
    }
  }

  /**
   * Returns the last N exceptions from the exception buffer.
   *
   * @param count the number of exceptions to retrieve
   * @return a list of the last N formatted exception entries
   */
  public List<String> getLastExceptions(int count) {
    synchronized (exceptionBuffer) {
      int size = exceptionBuffer.size();
      if (count >= size) {
        return new ArrayList<>(exceptionBuffer);
      }
      return new ArrayList<>(exceptionBuffer.subList(size - count, size));
    }
  }

  /**
   * Returns the last exception from the exception buffer.
   *
   * @return the last formatted exception entry, or null if buffer is empty
   */
  public String getLastException() {
    synchronized (exceptionBuffer) {
      if (exceptionBuffer.isEmpty()) {
        return null;
      }
      return exceptionBuffer.get(exceptionBuffer.size() - 1);
    }
  }

  /**
   * Clears the exception buffer.
   */
  public void clearExceptionBuffer() {
    synchronized (exceptionBuffer) {
      exceptionBuffer.clear();
    }
  }

  /**
   * Sets the maximum size for the exception buffer.
   *
   * @param maxSize the new maximum number of exceptions to buffer
   */
  public void setMaxExceptionBufferSize(int maxSize) {
    this.maxExceptionBufferSize = maxSize;
  }

  /**
   * Gets the maximum size of the exception buffer.
   *
   * @return the maximum number of exceptions that can be buffered
   */
  public int getMaxExceptionBufferSize() {
    return this.maxExceptionBufferSize;
  }

  /**
   * Sets the truncate back-to value for the exception buffer.
   *
   * @param truncateBackTo the number of most recent exceptions to keep when
   *                       trimming
   */
  public void setTruncateExceptionBackTo(int truncateBackTo) {
    this.truncateExceptionBackTo = truncateBackTo;
  }

  /**
   * Gets the truncate back-to value for the exception buffer.
   *
   * @return the number of most recent exceptions kept when trimming
   */
  public int getTruncateExceptionBackTo() {
    return this.truncateExceptionBackTo;
  }

  // ========== Static Helper Methods ==========

  /**
   * Gets the InMemoryAppender instance from the Log4j2 configuration. This
   * assumes the appender is registered with the name "INMEMORY".
   * 
   * @return the InMemoryAppender instance, or null if not found
   */
  public static InMemoryAppender getInstance() {
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    Configuration config = context.getConfiguration();
    return (InMemoryAppender) config.getAppender("INMEMORY");
  }

  /**
   * Static helper to get the last exception from the in-memory log buffer.
   * 
   * @return the last exception with stack trace, or null if none
   */
  public static String getLastExceptionStatic() {
    InMemoryAppender appender = getInstance();
    if (appender != null) {
      return appender.getLastException();
    }
    return null;
  }

  /**
   * Static helper to get the last N exceptions from the in-memory log buffer.
   * 
   * @param count the number of exceptions to retrieve
   * @return a list of exception stack traces
   */
  public static List<String> getLastExceptionsStatic(int count) {
    InMemoryAppender appender = getInstance();
    if (appender != null) {
      return appender.getLastExceptions(count);
    }
    return List.of();
  }

  /**
   * Static helper to get all exceptions from the in-memory log buffer.
   * 
   * @return a list of all exception stack traces
   */
  public static List<String> getAllExceptionsStatic() {
    InMemoryAppender appender = getInstance();
    if (appender != null) {
      return appender.getExceptionBuffer();
    }
    return List.of();
  }

  /**
   * Static helper to clear the exception buffer.
   */
  public static void clearExceptionsStatic() {
    InMemoryAppender appender = getInstance();
    if (appender != null) {
      appender.clearExceptionBuffer();
    }
  }
}
