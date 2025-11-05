package com.bitsapplied.descartes.tools;

import java.util.Collections;
import java.util.Map;

/**
 * Exception thrown when a tool execution fails. Carries an error code and
 * optional structured data for detailed error reporting.
 */
public class ToolExecutionException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final int errorCode;
  private final Map<String, Object> errorData;

  /**
   * Creates a tool execution exception with an error code and message.
   *
   * @param errorCode the error code
   * @param message   the error message
   */
  public ToolExecutionException(int errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
    this.errorData = Collections.emptyMap();
  }

  /**
   * Creates a tool execution exception with an error code, message, and cause.
   *
   * @param errorCode the error code
   * @param message   the error message
   * @param cause     the underlying cause
   */
  public ToolExecutionException(int errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
    this.errorData = Collections.emptyMap();
  }

  /**
   * Creates a tool execution exception with an error code, message, and
   * structured error data.
   *
   * @param errorCode the error code
   * @param message   the error message
   * @param errorData structured error data (e.g., tool name, details, original
   *                  code)
   */
  public ToolExecutionException(int errorCode, String message, Map<String, Object> errorData) {
    super(message);
    this.errorCode = errorCode;
    this.errorData = errorData != null ? Map.copyOf(errorData) : Collections.emptyMap();
  }

  /**
   * Creates a tool execution exception with an error code, message, cause, and
   * structured error data.
   *
   * @param errorCode the error code
   * @param message   the error message
   * @param cause     the underlying cause
   * @param errorData structured error data (e.g., tool name, details, original
   *                  code)
   */
  public ToolExecutionException(int errorCode, String message, Throwable cause, Map<String, Object> errorData) {
    super(message, cause);
    this.errorCode = errorCode;
    this.errorData = errorData != null ? Map.copyOf(errorData) : Collections.emptyMap();
  }

  /**
   * Gets the error code associated with this exception.
   *
   * @return the error code
   */
  public int getErrorCode() {
    return errorCode;
  }

  /**
   * Gets the structured error data associated with this exception.
   *
   * @return immutable map of error data (never null, may be empty)
   */
  public Map<String, Object> getErrorData() {
    return errorData;
  }
}
