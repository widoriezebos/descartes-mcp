package com.bitsapplied.descartes.debugger.exceptions;

/**
 * Exception thrown when debugger operations fail.
 *
 * <p>
 * This exception carries a {@link DebuggerErrorCode} to provide structured
 * error information for proper error handling and user feedback.
 */
public class DebuggerException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final DebuggerErrorCode errorCode;

  /**
   * Creates a debugger exception with an error code and message.
   *
   * @param errorCode the error code
   * @param message   additional error details
   */
  public DebuggerException(DebuggerErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  /**
   * Creates a debugger exception with an error code, message, and cause.
   *
   * @param errorCode the error code
   * @param message   additional error details
   * @param cause     the underlying cause
   */
  public DebuggerException(DebuggerErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  /**
   * Gets the error code associated with this exception.
   *
   * @return the error code
   */
  public DebuggerErrorCode getErrorCode() {
    return errorCode;
  }

  /**
   * Gets the full error message including the error code.
   *
   * @return the formatted error message
   */
  @Override
  public String getMessage() {
    if (errorCode == null) {
      return super.getMessage();
    }
    return String.format("[%s] %s: %s", errorCode.getCode(), errorCode.getMessage(), super.getMessage());
  }
}
