package com.bitsapplied.descartes.tools;

import java.util.Map;

/**
 * Sealed interface representing the result of a tool execution. Can be either a
 * Success or an Error.
 *
 * <p>
 * This design enforces explicit error handling at compile time and provides a
 * type-safe way to represent tool execution outcomes.
 */
public sealed interface ToolResponse permits ToolResponse.Success, ToolResponse.Error {

  /**
   * Successful tool execution with content and optional metadata.
   *
   * @param content  the result content (typically JSON string)
   * @param metadata optional metadata about the execution (e.g., execution time,
   *                 warnings)
   */
  record Success(String content, Map<String, Object> metadata) implements ToolResponse {
    /**
     * Creates a success response with no metadata.
     *
     * @param content the result content
     */
    public Success(String content) {
      this(content, Map.of());
    }
  }

  /**
   * Failed tool execution with error code, message, and details.
   *
   * @param code    the error code (from DebuggerErrorCode or custom)
   * @param message the error message
   * @param details additional error details (stack trace, context, etc.)
   */
  record Error(int code, String message, String details) implements ToolResponse {
    /**
     * Creates an error response with no details.
     *
     * @param code    the error code
     * @param message the error message
     */
    public Error(int code, String message) {
      this(code, message, "");
    }
  }

  /**
   * Factory method to create a success response.
   *
   * @param content the result content
   * @return a Success response
   */
  static ToolResponse success(String content) {
    return new Success(content);
  }

  /**
   * Factory method to create a success response with metadata.
   *
   * @param content  the result content
   * @param metadata execution metadata
   * @return a Success response
   */
  static ToolResponse success(String content, Map<String, Object> metadata) {
    return new Success(content, metadata);
  }

  /**
   * Factory method to create an error response.
   *
   * @param code    the error code
   * @param message the error message
   * @return an Error response
   */
  static ToolResponse error(int code, String message) {
    return new Error(code, message, "");
  }

  /**
   * Factory method to create an error response with details.
   *
   * @param code    the error code
   * @param message the error message
   * @param details additional error details
   * @return an Error response
   */
  static ToolResponse error(int code, String message, String details) {
    return new Error(code, message, details);
  }
}
