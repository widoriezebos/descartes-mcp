package com.bitsapplied.descartes.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Utility class for extracting and parsing parameters from Map<String, Object>
 * parameter maps.
 * <p>
 * This class consolidates parameter extraction logic that was previously
 * duplicated across multiple tool classes. It handles:
 * <ul>
 * <li>Type coercion (e.g., String to Number, Number to String)</li>
 * <li>Default value handling for optional parameters</li>
 * <li>Required parameter validation</li>
 * <li>Range validation for numeric parameters</li>
 * <li>Collection handling (List, String[], single values)</li>
 * </ul>
 * <p>
 * All methods are null-safe and handle common type conversion scenarios
 * gracefully.
 *
 * @see com.bitsapplied.descartes.debugger.DebuggerParameterUtils
 */
public final class ParameterUtils {

  private ParameterUtils() {
    // Utility class, no instantiation
  }

  // ==================== Optional Parameters (return null/default if missing)
  // ====================

  /**
   * Get a string parameter with a default value.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing or null
   * @return the parameter value as a String, or defaultValue if not found
   */
  public static String getString(Map<String, Object> params, String key, String defaultValue) {
    if (params == null) {
      return defaultValue;
    }
    Object value = params.get(key);
    if (value == null) {
      return defaultValue;
    }
    return value.toString();
  }

  /**
   * Get an integer parameter with a default value. Handles both Number and String
   * types.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing, null, or
   *                     cannot be parsed
   * @return the parameter value as an int, or defaultValue if not found
   */
  public static Integer getInt(Map<String, Object> params, String key, Integer defaultValue) {
    if (params == null) {
      return defaultValue;
    }
    Object value = params.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String) {
      try {
        return Integer.parseInt((String) value);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    throw new IllegalArgumentException(
        "Parameter '" + key + "' must be a number, but got " + value.getClass().getSimpleName());
  }

  /**
   * Get a long parameter with a default value. Handles both Number and String
   * types.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing, null, or
   *                     cannot be parsed
   * @return the parameter value as a long, or defaultValue if not found
   */
  public static Long getLong(Map<String, Object> params, String key, Long defaultValue) {
    if (params == null) {
      return defaultValue;
    }
    Object value = params.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        return Long.parseLong((String) value);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    throw new IllegalArgumentException(
        "Parameter '" + key + "' must be a number, but got " + value.getClass().getSimpleName());
  }

  /**
   * Get a boolean parameter with a default value. Handles both Boolean and String
   * types.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing or null
   * @return the parameter value as a boolean, or defaultValue if not found
   */
  public static Boolean getBoolean(Map<String, Object> params, String key, Boolean defaultValue) {
    if (params == null) {
      return defaultValue;
    }
    Object value = params.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof String) {
      return Boolean.parseBoolean((String) value);
    }
    throw new IllegalArgumentException(
        "Parameter '" + key + "' must be a boolean, but got " + value.getClass().getSimpleName());
  }

  /**
   * Get a double parameter with a default value. Handles both Number and String
   * types.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing, null, or
   *                     cannot be parsed
   * @return the parameter value as a double, or defaultValue if not found
   */
  public static Double getDouble(Map<String, Object> params, String key, Double defaultValue) {
    if (params == null) {
      return defaultValue;
    }
    Object value = params.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    if (value instanceof String) {
      try {
        return Double.parseDouble((String) value);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    throw new IllegalArgumentException(
        "Parameter '" + key + "' must be a number, but got " + value.getClass().getSimpleName());
  }

  // ==================== Required Parameters (throw exception if missing)
  // ====================

  /**
   * Get a required string parameter.
   *
   * @param params the parameter map
   * @param key    the parameter key
   * @return the parameter value as a String
   * @throws IllegalArgumentException if parameter is missing or null
   */
  public static String getRequiredString(Map<String, Object> params, String key) {
    if (params == null || !params.containsKey(key) || params.get(key) == null) {
      throw new IllegalArgumentException("Required parameter '" + key + "' is missing");
    }
    return params.get(key).toString();
  }

  /**
   * Get a required integer parameter. Handles both Number and String types.
   *
   * @param params the parameter map
   * @param key    the parameter key
   * @return the parameter value as an int
   * @throws IllegalArgumentException if parameter is missing, null, or cannot be
   *                                  parsed
   */
  public static int getRequiredInt(Map<String, Object> params, String key) {
    if (params == null || !params.containsKey(key)) {
      throw new IllegalArgumentException("Required parameter '" + key + "' is missing");
    }
    Object value = params.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Required parameter '" + key + "' is null");
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String) {
      try {
        return Integer.parseInt((String) value);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Required parameter '" + key + "' must be a valid integer, but got: " + value);
      }
    }
    throw new IllegalArgumentException(
        "Required parameter '" + key + "' must be a number, but got " + value.getClass().getSimpleName());
  }

  /**
   * Get a required long parameter. Handles both Number and String types.
   *
   * @param params the parameter map
   * @param key    the parameter key
   * @return the parameter value as a long
   * @throws IllegalArgumentException if parameter is missing, null, or cannot be
   *                                  parsed
   */
  public static long getRequiredLong(Map<String, Object> params, String key) {
    if (params == null || !params.containsKey(key)) {
      throw new IllegalArgumentException("Required parameter '" + key + "' is missing");
    }
    Object value = params.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Required parameter '" + key + "' is null");
    }
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        return Long.parseLong((String) value);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Required parameter '" + key + "' must be a valid long, but got: " + value);
      }
    }
    throw new IllegalArgumentException(
        "Required parameter '" + key + "' must be a number, but got " + value.getClass().getSimpleName());
  }

  // ==================== Validated Parameters (with range checks)
  // ====================

  /**
   * Get an integer parameter with range validation.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing or null
   * @param min          the minimum allowed value (inclusive)
   * @param max          the maximum allowed value (inclusive)
   * @return the parameter value as an int
   * @throws IllegalArgumentException if the value is outside the allowed range
   */
  public static int getInt(Map<String, Object> params, String key, int defaultValue, int min, int max) {
    Integer value = getInt(params, key, defaultValue);
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          "Parameter '" + key + "' must be between " + min + " and " + max + ", but got: " + value);
    }
    return value;
  }

  /**
   * Get a double parameter with range validation.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing or null
   * @param min          the minimum allowed value (inclusive)
   * @param max          the maximum allowed value (inclusive)
   * @return the parameter value as a double
   * @throws IllegalArgumentException if the value is outside the allowed range
   */
  public static double getDouble(Map<String, Object> params, String key, double defaultValue, double min, double max) {
    Double value = getDouble(params, key, defaultValue);
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          "Parameter '" + key + "' must be between " + min + " and " + max + ", but got: " + value);
    }
    return value;
  }

  // ==================== Collection Handling ====================

  /**
   * Get a string list parameter. Handles List, Collection, String[] and single
   * String values.
   *
   * @param params the parameter map
   * @param key    the parameter key
   * @return the parameter value as a List<String>, or empty list if not found
   */
  public static List<String> getStringList(Map<String, Object> params, String key) {
    if (params == null) {
      return new ArrayList<>();
    }
    Object value = params.get(key);
    if (value == null) {
      return new ArrayList<>();
    }

    // Handle List
    if (value instanceof List) {
      List<String> result = new ArrayList<>();
      for (Object item : (List<?>) value) {
        if (item != null) {
          result.add(item.toString());
        }
      }
      return result;
    }

    // Handle Collection
    if (value instanceof Collection) {
      List<String> result = new ArrayList<>();
      for (Object item : (Collection<?>) value) {
        if (item != null) {
          result.add(item.toString());
        }
      }
      return result;
    }

    // Handle array
    if (value.getClass().isArray()) {
      List<String> result = new ArrayList<>();
      Object[] array = (Object[]) value;
      for (Object item : array) {
        if (item != null) {
          result.add(item.toString());
        }
      }
      return result;
    }

    // Handle single value
    List<String> result = new ArrayList<>();
    result.add(value.toString());
    return result;
  }

  /**
   * Get a string array parameter. Handles List, Collection, String[] and single
   * String values.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing or null
   * @return the parameter value as a String[], or defaultValue if not found
   */
  public static String[] getStringArray(Map<String, Object> params, String key, String[] defaultValue) {
    if (params == null || !params.containsKey(key) || params.get(key) == null) {
      return defaultValue;
    }
    List<String> list = getStringList(params, key);
    return list.toArray(new String[0]);
  }
}
