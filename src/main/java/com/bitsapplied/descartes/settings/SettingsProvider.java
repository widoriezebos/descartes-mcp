package com.bitsapplied.descartes.settings;

/**
 * Interface for providing settings to Descartes components. This allows
 * integration with different settings mechanisms.
 */
public interface SettingsProvider {

  /**
   * Gets a string setting value.
   * 
   * @param key          the setting key
   * @param defaultValue the default value if the setting is not found
   * @return the setting value
   */
  String getString(String key, String defaultValue);

  /**
   * Gets an integer setting value.
   * 
   * @param key          the setting key
   * @param defaultValue the default value if the setting is not found
   * @return the setting value
   */
  int getInt(String key, int defaultValue);

  /**
   * Gets a boolean setting value.
   * 
   * @param key          the setting key
   * @param defaultValue the default value if the setting is not found
   * @return the setting value
   */
  boolean getBoolean(String key, boolean defaultValue);

  /**
   * Gets a double setting value.
   * 
   * @param key          the setting key
   * @param defaultValue the default value if the setting is not found
   * @return the setting value
   */
  double getDouble(String key, double defaultValue);

  /**
   * Sets a string setting value.
   * 
   * @param key   the setting key
   * @param value the setting value
   */
  void setString(String key, String value);

  /**
   * Sets an integer setting value.
   * 
   * @param key   the setting key
   * @param value the setting value
   */
  void setInt(String key, int value);

  /**
   * Sets a boolean setting value.
   * 
   * @param key   the setting key
   * @param value the setting value
   */
  void setBoolean(String key, boolean value);
}