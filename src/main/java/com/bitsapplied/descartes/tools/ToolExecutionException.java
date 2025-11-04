package com.bitsapplied.descartes.tools;

/**
 * Exception thrown when a tool execution fails. Carries an error code for
 * structured error handling.
 */
public class ToolExecutionException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final int errorCode;

  /**
   * Creates a tool execution exception with an error code and message.
   *
   * @param errorCode the error code
   * @param message   the error message
   */
  public ToolExecutionException(int errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
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
  }

  /**
   * Gets the error code associated with this exception.
   *
   * @return the error code
   */
  public int getErrorCode() {
    return errorCode;
  }
}
