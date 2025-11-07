package com.bitsapplied.descartes.tools;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sealed interface representing the result of a tool execution. Can be either a
 * Success or an Error.
 *
 * <p>
 * This design enforces explicit error handling at compile time and provides a
 * type-safe way to represent tool execution outcomes.
 *
 * <p>
 * <b>Response Formats:</b> Tools can return responses in two formats:
 * <ul>
 * <li><b>Text format:</b> Plain text content (default)</li>
 * <li><b>JSON format:</b> Structured data that will be embedded directly in MCP
 * response (avoids double-encoding)</li>
 * </ul>
 */
public sealed interface ToolResponse permits ToolResponse.Success, ToolResponse.Error {

  /** Metadata key indicating response format: "text" or "json" */
  String METADATA_FORMAT = "_format";

  /** Format value for plain text responses */
  String FORMAT_TEXT = "text";

  /** Format value for structured JSON responses */
  String FORMAT_JSON = "json";

  /** Shared ObjectMapper for JSON serialization */
  ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

  /**
   * Factory method to create a success response with structured JSON data.
   *
   * <p>
   * This method avoids double-encoding by marking the response as JSON format.
   * MCPServer will embed the data directly instead of wrapping it as a text
   * string.
   *
   * @param data the structured data to return
   * @return a Success response with JSON format metadata
   */
  static ToolResponse successJson(Map<String, Object> data) {
    try {
      String jsonContent = OBJECT_MAPPER.writeValueAsString(data);
      Map<String, Object> metadata = new HashMap<>();
      metadata.put(METADATA_FORMAT, FORMAT_JSON);
      return new Success(jsonContent, metadata);
    } catch (JsonProcessingException e) {
      // This should rarely happen with Map<String, Object>
      return error(ToolErrorCode.INTERNAL_ERROR, "Failed to serialize response to JSON: " + e.getMessage());
    }
  }

  /**
   * Factory method to create a success response with structured JSON data and
   * additional metadata.
   *
   * @param data           the structured data to return
   * @param additionalMeta additional metadata to include
   * @return a Success response with JSON format metadata
   */
  static ToolResponse successJson(Map<String, Object> data, Map<String, Object> additionalMeta) {
    try {
      String jsonContent = OBJECT_MAPPER.writeValueAsString(data);
      Map<String, Object> metadata = new HashMap<>(additionalMeta);
      metadata.put(METADATA_FORMAT, FORMAT_JSON);
      return new Success(jsonContent, metadata);
    } catch (JsonProcessingException e) {
      return error(ToolErrorCode.INTERNAL_ERROR, "Failed to serialize response to JSON: " + e.getMessage());
    }
  }

  /**
   * Convenience factory for validation errors.
   *
   * @param message error message
   * @return validation error response
   */
  static ToolResponse validationError(String message) {
    return error(ToolErrorCode.VALIDATION_FAILED, message);
  }

  /**
   * Convenience factory for missing parameter errors.
   */
  static ToolResponse missingParameter(String parameterName) {
    String displayName = "operation".equalsIgnoreCase(parameterName) ? "Operation" : parameterName;
    return error(ToolErrorCode.MISSING_REQUIRED_PARAMETER,
        String.format("Missing required parameter: %s (%s is required)", parameterName, displayName));
  }

  /**
   * Convenience factory for invalid parameter errors.
   */
  static ToolResponse invalidParameter(String parameterName, String details) {
    return error(ToolErrorCode.INVALID_PARAMETER_VALUE, String.format("Invalid value for '%s'%s", parameterName,
        details != null && !details.isBlank() ? ": " + details : ""));
  }

  /**
   * Convenience factory for unsupported operation errors.
   */
  static ToolResponse unsupportedOperation(String operation, String supportedOperations) {
    String suffix = supportedOperations == null || supportedOperations.isBlank() ? ""
        : ". Supported operations: " + supportedOperations;
    return error(ToolErrorCode.UNSUPPORTED_OPERATION,
        "Unsupported operation: " + operation + " (Unknown operation)" + suffix);
  }

  /**
   * Convenience factory for precondition failures (e.g., missing agent, thread
   * not suspended).
   */
  static ToolResponse preconditionFailed(String message) {
    return error(ToolErrorCode.PRECONDITION_FAILED, message);
  }

  /**
   * Convenience factory for resource unavailable errors.
   */
  static ToolResponse resourceUnavailable(String message) {
    return error(ToolErrorCode.RESOURCE_UNAVAILABLE, message);
  }

  /**
   * Convenience factory for timeout errors.
   */
  static ToolResponse timeout(String message) {
    return error(ToolErrorCode.TIMEOUT, message);
  }

  /**
   * Convenience factory for execution failures.
   */
  static ToolResponse executionFailed(String message) {
    return error(ToolErrorCode.EXECUTION_FAILED, message);
  }

  /**
   * Convenience factory for internal errors.
   */
  static ToolResponse internalError(String message, Throwable throwable) {
    String details = throwable != null ? throwable.getMessage() : "";
    return error(ToolErrorCode.INTERNAL_ERROR, message, details);
  }
}
