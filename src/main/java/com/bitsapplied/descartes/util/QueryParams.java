package com.bitsapplied.descartes.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for parsing and accessing query parameters from URI query
 * strings.
 */
public class QueryParams {
  private final Map<String, List<String>> params = new HashMap<>();

  /**
   * Creates a QueryParams instance from a query string.
   * 
   * @param queryString the URL query string (e.g., "key1=value1&key2=value2")
   */
  public QueryParams(String queryString) {
    if (queryString != null && !queryString.isEmpty()) {
      for (String param : queryString.split("&")) {
        String[] kv = param.split("=", 2);
        String key = kv[0];
        String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
        params.computeIfAbsent(key, _ -> new ArrayList<>()).add(value);
      }
    }
  }

  /**
   * Gets the first value for a parameter.
   * 
   * @param key the parameter key
   * @return the first value, or null if the parameter doesn't exist
   */
  public String get(String key) {
    List<String> values = params.get(key);
    return values != null && !values.isEmpty() ? values.get(0) : null;
  }

  /**
   * Gets the first value for a parameter with a default value.
   * 
   * @param key          the parameter key
   * @param defaultValue the default value to return if the parameter doesn't
   *                     exist
   * @return the first value, or the default value if the parameter doesn't exist
   */
  public String get(String key, String defaultValue) {
    String value = get(key);
    return value != null ? value : defaultValue;
  }

  /**
   * Gets all values for a parameter.
   * 
   * @param key the parameter key
   * @return an array of all values, or null if the parameter doesn't exist
   */
  public String[] getAll(String key) {
    List<String> values = params.get(key);
    return values != null ? values.toArray(new String[0]) : null;
  }

  /**
   * Checks if a parameter exists.
   * 
   * @param key the parameter key
   * @return true if the parameter exists, false otherwise
   */
  public boolean has(String key) {
    return params.containsKey(key);
  }
}