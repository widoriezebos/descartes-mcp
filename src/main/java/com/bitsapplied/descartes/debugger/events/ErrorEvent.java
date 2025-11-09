package com.bitsapplied.descartes.debugger.events;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Represents a recoverable error that occurred during event processing.
 *
 * <p>
 * Error events are emitted when the EventHub encounters exceptions during event
 * loop processing or individual event handling. Unlike traditional error
 * handling that terminates the stream, error events allow subscribers to
 * observe errors while the event stream continues processing.
 *
 * <p>
 * <b>Severity Levels:</b>
 * <ul>
 * <li>{@link Severity#RECOVERABLE} - Transient error, logged and ignored</li>
 * <li>{@link Severity#WARNING} - May affect functionality, monitor</li>
 * <li>{@link Severity#CRITICAL} - Significant issue, may require
 * intervention</li>
 * </ul>
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>{@code
 * eventHub.eventsOfType(ErrorEvent.class).subscribe(error -> {
 *   if (error.severity() != Severity.RECOVERABLE) {
 *     alerting.notify("Debugger error: " + error.context());
 *   }
 *   metrics.increment("debugger.errors", "severity", error.severity().name());
 * });
 * }</pre>
 */
public record ErrorEvent(Exception exception, long timestamp, String context, Severity severity)
    implements StreamEvent {

  /**
   * Severity levels for error events.
   */
  public enum Severity {
    /**
     * Recoverable error - event processing continues normally. These are typically
     * transient issues that don't affect overall debugger operation.
     */
    RECOVERABLE,

    /**
     * Warning - may affect some functionality. Indicates issues that could impact
     * specific features but don't prevent overall debugger operation.
     */
    WARNING,

    /**
     * Critical error - significant issue requiring attention. May indicate
     * systematic problems or failures that could prevent debugging.
     */
    CRITICAL
  }

  /**
   * Creates an error event with the current timestamp.
   *
   * @param exception the exception that occurred
   * @param context   description of what was happening when the error occurred
   * @param severity  severity level of the error
   */
  public ErrorEvent(Exception exception, String context, Severity severity) {
    this(exception, System.currentTimeMillis(), context, severity);
  }

  /**
   * Gets a brief description of this error event.
   *
   * @return formatted error summary
   */
  @Override
  public String toShortString() {
    return String.format("ErrorEvent[%s]: %s - %s", severity, context, exception.getMessage());
  }

  /**
   * Gets the age of this error in milliseconds.
   *
   * @return age in milliseconds
   */
  public long getAgeMs() {
    return System.currentTimeMillis() - timestamp;
  }

  /**
   * Gets the exception message.
   *
   * @return exception message or "Unknown error" if null
   */
  public String getMessage() {
    return exception != null ? exception.getMessage() : "Unknown error";
  }

  /**
   * Gets the exception class name.
   *
   * @return exception type name
   */
  public String getExceptionType() {
    return exception != null ? exception.getClass().getSimpleName() : "UnknownException";
  }

  /**
   * Gets the full stack trace as a string.
   *
   * @return formatted stack trace
   */
  public String getStackTrace() {
    if (exception == null) {
      return "";
    }
    StringWriter sw = new StringWriter();
    exception.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }

  /**
   * Creates a recoverable error event.
   *
   * @param exception the exception
   * @param context   error context
   * @return error event with RECOVERABLE severity
   */
  public static ErrorEvent recoverable(Exception exception, String context) {
    return new ErrorEvent(exception, context, Severity.RECOVERABLE);
  }

  /**
   * Creates a warning error event.
   *
   * @param exception the exception
   * @param context   error context
   * @return error event with WARNING severity
   */
  public static ErrorEvent warning(Exception exception, String context) {
    return new ErrorEvent(exception, context, Severity.WARNING);
  }

  /**
   * Creates a critical error event.
   *
   * @param exception the exception
   * @param context   error context
   * @return error event with CRITICAL severity
   */
  public static ErrorEvent critical(Exception exception, String context) {
    return new ErrorEvent(exception, context, Severity.CRITICAL);
  }

  /**
   * Gets a detailed description of this error event.
   *
   * @return formatted error details
   */
  @Override
  public String toString() {
    return String.format("ErrorEvent{severity=%s, context='%s', exception=%s, age=%dms}", severity, context,
        getExceptionType(), getAgeMs());
  }
}
