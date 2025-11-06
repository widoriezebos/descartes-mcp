package com.bitsapplied.descartes.debugger;

import java.util.List;
import java.util.Map;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.bitsapplied.descartes.util.ParameterUtils;

/**
 * Debugger-specific parameter utilities that wrap {@link ParameterUtils} and
 * convert {@link IllegalArgumentException} to {@link DebuggerException} with
 * appropriate error codes.
 * <p>
 * This allows debugger tools to maintain their specific exception handling
 * contract while using the shared parameter parsing logic.
 *
 * @see ParameterUtils
 * @see DebuggerException
 */
public final class DebuggerParameterUtils {

  private DebuggerParameterUtils() {
    // Utility class, no instantiation
  }

  // ==================== Optional Parameters ====================

  /**
   * Get a string parameter with a default value.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing or null
   * @return the parameter value as a String, or defaultValue if not found
   * @throws DebuggerException if parameter exists but has invalid type
   */
  public static String getString(Map<String, Object> params, String key, String defaultValue) {
    try {
      return ParameterUtils.getString(params, key, defaultValue);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }

  /**
   * Get an integer parameter with a default value.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing, null, or
   *                     cannot be parsed
   * @return the parameter value as an int, or defaultValue if not found
   * @throws DebuggerException if parameter exists but has invalid type
   */
  public static Integer getInt(Map<String, Object> params, String key, Integer defaultValue) {
    try {
      return ParameterUtils.getInt(params, key, defaultValue);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }

  /**
   * Get a long parameter with a default value.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing, null, or
   *                     cannot be parsed
   * @return the parameter value as a long, or defaultValue if not found
   * @throws DebuggerException if parameter exists but has invalid type
   */
  public static Long getLong(Map<String, Object> params, String key, Long defaultValue) {
    try {
      return ParameterUtils.getLong(params, key, defaultValue);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }

  /**
   * Get a boolean parameter with a default value.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing or null
   * @return the parameter value as a boolean, or defaultValue if not found
   * @throws DebuggerException if parameter exists but has invalid type
   */
  public static Boolean getBoolean(Map<String, Object> params, String key, Boolean defaultValue) {
    try {
      return ParameterUtils.getBoolean(params, key, defaultValue);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }

  /**
   * Get a double parameter with a default value.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing, null, or
   *                     cannot be parsed
   * @return the parameter value as a double, or defaultValue if not found
   * @throws DebuggerException if parameter exists but has invalid type
   */
  public static Double getDouble(Map<String, Object> params, String key, Double defaultValue) {
    try {
      return ParameterUtils.getDouble(params, key, defaultValue);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }

  // ==================== Required Parameters ====================

  /**
   * Get a required string parameter.
   *
   * @param params the parameter map
   * @param key    the parameter key
   * @return the parameter value as a String
   * @throws DebuggerException if parameter is missing, null, or has invalid type
   */
  public static String getRequiredString(Map<String, Object> params, String key) {
    try {
      return ParameterUtils.getRequiredString(params, key);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }

  /**
   * Get a required integer parameter.
   *
   * @param params the parameter map
   * @param key    the parameter key
   * @return the parameter value as an int
   * @throws DebuggerException if parameter is missing, null, cannot be parsed, or
   *                           has invalid type
   */
  public static int getRequiredInt(Map<String, Object> params, String key) {
    try {
      return ParameterUtils.getRequiredInt(params, key);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }

  /**
   * Get a required long parameter.
   *
   * @param params the parameter map
   * @param key    the parameter key
   * @return the parameter value as a long
   * @throws DebuggerException if parameter is missing, null, cannot be parsed, or
   *                           has invalid type
   */
  public static long getRequiredLong(Map<String, Object> params, String key) {
    try {
      return ParameterUtils.getRequiredLong(params, key);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }

  // ==================== Validated Parameters ====================

  /**
   * Get an integer parameter with range validation.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing or null
   * @param min          the minimum allowed value (inclusive)
   * @param max          the maximum allowed value (inclusive)
   * @return the parameter value as an int
   * @throws DebuggerException if the value is outside the allowed range or has
   *                           invalid type
   */
  public static int getInt(Map<String, Object> params, String key, int defaultValue, int min, int max) {
    try {
      return ParameterUtils.getInt(params, key, defaultValue, min, max);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
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
   * @throws DebuggerException if the value is outside the allowed range or has
   *                           invalid type
   */
  public static double getDouble(Map<String, Object> params, String key, double defaultValue, double min, double max) {
    try {
      return ParameterUtils.getDouble(params, key, defaultValue, min, max);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }

  // ==================== Collection Handling ====================

  /**
   * Get a string list parameter.
   *
   * @param params the parameter map
   * @param key    the parameter key
   * @return the parameter value as a List<String>, or empty list if not found
   * @throws DebuggerException if parameter has invalid type
   */
  public static List<String> getStringList(Map<String, Object> params, String key) {
    try {
      return ParameterUtils.getStringList(params, key);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }

  /**
   * Get a string array parameter.
   *
   * @param params       the parameter map
   * @param key          the parameter key
   * @param defaultValue the default value if parameter is missing or null
   * @return the parameter value as a String[], or defaultValue if not found
   * @throws DebuggerException if parameter has invalid type
   */
  public static String[] getStringArray(Map<String, Object> params, String key, String[] defaultValue) {
    try {
      return ParameterUtils.getStringArray(params, key, defaultValue);
    } catch (IllegalArgumentException e) {
      throw new DebuggerException(DebuggerErrorCode.INVALID_PARAMETERS, e.getMessage(), e);
    }
  }
}
