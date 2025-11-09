package com.bitsapplied.descartes.util;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class for consistent parameter validation across MCP tools.
 *
 * <p>
 * Provides type-safe parameter extraction with clear error messages. All
 * methods throw {@link ValidationException} with descriptive error messages
 * when validation fails.
 *
 * <p>
 * <b>Usage Example:</b>
 *
 * <pre>
 * public ToolResponse executeAsync(Map&lt;String, Object&gt; arguments) {
 *   try {
 *     String operation = ParameterValidator.requireString(arguments, "operation");
 *     int threadId = ParameterValidator.requireInt(arguments, "thread_id");
 *     String optional = ParameterValidator.optionalString(arguments, "filter", "default");
 *
 *     // Tool logic here
 *   } catch (ValidationException e) {
 *     return ToolResponse.error(ERROR_INVALID_PARAMS, e.getMessage());
 *   }
 * }
 * </pre>
 */
public final class ParameterValidator {

  private ParameterValidator() {
    // Utility class - no instantiation
  }

  /**
   * Exception thrown when parameter validation fails.
   */
  public static class ValidationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
      super(message);
    }

    public ValidationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  // ========== Required Parameters ==========

  /**
   * Extracts a required string parameter.
   *
   * @param params the parameter map
   * @param name   the parameter name
   * @return the string value
   * @throws ValidationException if parameter is missing, null, or not a string
   */
  public static String requireString(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      throw new ValidationException(String.format("Parameter '%s' is required", name));
    }
    if (!(value instanceof String)) {
      throw new ValidationException(
          String.format("Parameter '%s' must be a string, got %s", name, value.getClass().getSimpleName()));
    }
    return (String) value;
  }

  /**
   * Extracts a required non-empty string parameter.
   *
   * @param params the parameter map
   * @param name   the parameter name
   * @return the non-empty string value
   * @throws ValidationException if parameter is missing, null, empty, or not a
   *                             string
   */
  public static String requireNonEmptyString(Map<String, Object> params, String name) {
    String value = requireString(params, name);
    if (value.trim().isEmpty()) {
      throw new ValidationException(String.format("Parameter '%s' must not be empty", name));
    }
    return value;
  }

  /**
   * Extracts a required integer parameter.
   *
   * @param params the parameter map
   * @param name   the parameter name
   * @return the integer value
   * @throws ValidationException if parameter is missing, null, or not convertible
   *                             to int
   */
  public static int requireInt(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      throw new ValidationException(String.format("Parameter '%s' is required", name));
    }
    return toInt(value, name);
  }

  /**
   * Extracts a required long parameter.
   *
   * @param params the parameter map
   * @param name   the parameter name
   * @return the long value
   * @throws ValidationException if parameter is missing, null, or not convertible
   *                             to long
   */
  public static long requireLong(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      throw new ValidationException(String.format("Parameter '%s' is required", name));
    }
    return toLong(value, name);
  }

  /**
   * Extracts a required boolean parameter.
   *
   * @param params the parameter map
   * @param name   the parameter name
   * @return the boolean value
   * @throws ValidationException if parameter is missing, null, or not a boolean
   */
  public static boolean requireBoolean(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      throw new ValidationException(String.format("Parameter '%s' is required", name));
    }
    if (!(value instanceof Boolean)) {
      throw new ValidationException(
          String.format("Parameter '%s' must be a boolean, got %s", name, value.getClass().getSimpleName()));
    }
    return (Boolean) value;
  }

  // ========== Optional Parameters ==========

  /**
   * Extracts an optional string parameter.
   *
   * @param params       the parameter map
   * @param name         the parameter name
   * @param defaultValue the default value if parameter is missing or null
   * @return the string value or default
   * @throws ValidationException if parameter exists but is not a string
   */
  public static String optionalString(Map<String, Object> params, String name, String defaultValue) {
    Object value = params.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof String)) {
      throw new ValidationException(
          String.format("Parameter '%s' must be a string, got %s", name, value.getClass().getSimpleName()));
    }
    return (String) value;
  }

  /**
   * Extracts an optional integer parameter.
   *
   * @param params       the parameter map
   * @param name         the parameter name
   * @param defaultValue the default value if parameter is missing or null
   * @return the integer value or default
   * @throws ValidationException if parameter exists but is not convertible to int
   */
  public static int optionalInt(Map<String, Object> params, String name, int defaultValue) {
    Object value = params.get(name);
    if (value == null) {
      return defaultValue;
    }
    return toInt(value, name);
  }

  /**
   * Extracts an optional long parameter.
   *
   * @param params       the parameter map
   * @param name         the parameter name
   * @param defaultValue the default value if parameter is missing or null
   * @return the long value or default
   * @throws ValidationException if parameter exists but is not convertible to
   *                             long
   */
  public static long optionalLong(Map<String, Object> params, String name, long defaultValue) {
    Object value = params.get(name);
    if (value == null) {
      return defaultValue;
    }
    return toLong(value, name);
  }

  /**
   * Extracts an optional boolean parameter.
   *
   * @param params       the parameter map
   * @param name         the parameter name
   * @param defaultValue the default value if parameter is missing or null
   * @return the boolean value or default
   * @throws ValidationException if parameter exists but is not a boolean
   */
  public static boolean optionalBoolean(Map<String, Object> params, String name, boolean defaultValue) {
    Object value = params.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean)) {
      throw new ValidationException(
          String.format("Parameter '%s' must be a boolean, got %s", name, value.getClass().getSimpleName()));
    }
    return (Boolean) value;
  }

  // ========== Enum Validation ==========

  /**
   * Validates that a required string parameter is one of the allowed values.
   *
   * @param params        the parameter map
   * @param name          the parameter name
   * @param allowedValues the set of allowed values
   * @return the validated string value
   * @throws ValidationException if parameter is missing, null, or not in allowed
   *                             values
   */
  public static String requireEnum(Map<String, Object> params, String name, Set<String> allowedValues) {
    String value = requireString(params, name);
    if (!allowedValues.contains(value)) {
      throw new ValidationException(
          String.format("Parameter '%s' must be one of %s, got '%s'", name, allowedValues, value));
    }
    return value;
  }

  /**
   * Validates that a required string parameter is one of the allowed values.
   *
   * @param params        the parameter map
   * @param name          the parameter name
   * @param allowedValues the list of allowed values
   * @return the validated string value
   * @throws ValidationException if parameter is missing, null, or not in allowed
   *                             values
   */
  public static String requireEnum(Map<String, Object> params, String name, List<String> allowedValues) {
    return requireEnum(params, name, Set.copyOf(allowedValues));
  }

  // ========== Range Validation ==========

  /**
   * Validates that an integer parameter is within a specified range.
   *
   * @param params the parameter map
   * @param name   the parameter name
   * @param min    the minimum allowed value (inclusive)
   * @param max    the maximum allowed value (inclusive)
   * @return the validated integer value
   * @throws ValidationException if parameter is missing, invalid, or out of range
   */
  public static int requireIntInRange(Map<String, Object> params, String name, int min, int max) {
    int value = requireInt(params, name);
    if (value < min || value > max) {
      throw new ValidationException(
          String.format("Parameter '%s' must be between %d and %d, got %d", name, min, max, value));
    }
    return value;
  }

  /**
   * Validates that an optional integer parameter is within a specified range.
   *
   * @param params       the parameter map
   * @param name         the parameter name
   * @param min          the minimum allowed value (inclusive)
   * @param max          the maximum allowed value (inclusive)
   * @param defaultValue the default value if parameter is missing
   * @return the validated integer value or default
   * @throws ValidationException if parameter is invalid or out of range
   */
  public static int optionalIntInRange(Map<String, Object> params, String name, int min, int max, int defaultValue) {
    Object value = params.get(name);
    if (value == null) {
      return defaultValue;
    }
    int intValue = toInt(value, name);
    if (intValue < min || intValue > max) {
      throw new ValidationException(
          String.format("Parameter '%s' must be between %d and %d, got %d", name, min, max, intValue));
    }
    return intValue;
  }

  // ========== Type Conversion Helpers ==========

  /**
   * Converts an object to int with clear error messages.
   *
   * @param value the value to convert
   * @param name  the parameter name (for error messages)
   * @return the int value
   * @throws ValidationException if conversion fails
   */
  private static int toInt(Object value, String name) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException e) {
      throw new ValidationException(String.format("Parameter '%s' must be a valid integer, got '%s'", name, value), e);
    }
  }

  /**
   * Converts an object to long with clear error messages.
   *
   * @param value the value to convert
   * @param name  the parameter name (for error messages)
   * @return the long value
   * @throws ValidationException if conversion fails
   */
  private static long toLong(Object value, String name) {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      throw new ValidationException(String.format("Parameter '%s' must be a valid integer, got '%s'", name, value), e);
    }
  }

  // ========== Nullable Optional Parameters ==========

  /**
   * Extracts an optional string parameter as Optional.
   *
   * @param params the parameter map
   * @param name   the parameter name
   * @return Optional containing the value, or empty if missing/null
   * @throws ValidationException if parameter exists but is not a string
   */
  public static Optional<String> getOptionalString(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof String)) {
      throw new ValidationException(
          String.format("Parameter '%s' must be a string, got %s", name, value.getClass().getSimpleName()));
    }
    return Optional.of((String) value);
  }

  /**
   * Extracts an optional integer parameter as Optional.
   *
   * @param params the parameter map
   * @param name   the parameter name
   * @return Optional containing the value, or empty if missing/null
   * @throws ValidationException if parameter exists but is not convertible to int
   */
  public static Optional<Integer> getOptionalInt(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      return Optional.empty();
    }
    return Optional.of(toInt(value, name));
  }

  /**
   * Extracts an optional long parameter as Optional.
   *
   * @param params the parameter map
   * @param name   the parameter name
   * @return Optional containing the value, or empty if missing/null
   * @throws ValidationException if parameter exists but is not convertible to
   *                             long
   */
  public static Optional<Long> getOptionalLong(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      return Optional.empty();
    }
    return Optional.of(toLong(value, name));
  }
}
