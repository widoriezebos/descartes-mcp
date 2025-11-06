package com.bitsapplied.descartes.settings;

/**
 * Type-safe settings accessor that wraps a SettingsProvider and adds support
 * for the Setting enum.
 * <p>
 * This class provides:
 * <ul>
 * <li>Type-safe access via Setting enum constants</li>
 * <li>System property override capability (System properties take
 * precedence)</li>
 * <li>Automatic default value handling from Setting enum</li>
 * <li>Backward compatibility with string-based key access</li>
 * </ul>
 * <p>
 * Pattern inspired by Morpheus Settings architecture, adapted for Descartes'
 * existing SettingsProvider interface.
 *
 * @see Setting
 * @see SettingsProvider
 */
public class Settings {

  private final SettingsProvider provider;

  /**
   * Create a Settings instance wrapping a SettingsProvider.
   *
   * @param provider the underlying settings provider
   */
  public Settings(SettingsProvider provider) {
    if (provider == null) {
      throw new IllegalArgumentException("SettingsProvider cannot be null");
    }
    this.provider = provider;
  }

  /**
   * Create a Settings instance with default settings (using DefaultSettings).
   */
  public Settings() {
    this(new DefaultSettings());
  }

  // ==================== Type-Safe Methods (Setting Enum) ====================

  /**
   * Get an integer setting value.
   * <p>
   * Lookup order: System property → SettingsProvider → Setting default
   *
   * @param setting the setting enum constant
   * @return the setting value
   */
  public synchronized int getInt(Setting setting) {
    String key = setting.key();
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      try {
        return Integer.parseInt(sysProp);
      } catch (NumberFormatException e) {
        // Fall through to provider
      }
    }
    return provider.getInt(key, setting.defaultValue(Integer.class));
  }

  /**
   * Get a long setting value.
   * <p>
   * Lookup order: System property → SettingsProvider → Setting default
   *
   * @param setting the setting enum constant
   * @return the setting value
   */
  public synchronized long getLong(Setting setting) {
    String key = setting.key();
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      try {
        return Long.parseLong(sysProp);
      } catch (NumberFormatException e) {
        // Fall through to provider
      }
    }
    // SettingsProvider doesn't have getLong, so we get as String and parse
    String value = provider.getString(key, setting.defaultString());
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return setting.defaultValue(Long.class);
    }
  }

  /**
   * Get a double setting value.
   * <p>
   * Lookup order: System property → SettingsProvider → Setting default
   *
   * @param setting the setting enum constant
   * @return the setting value
   */
  public synchronized double getDouble(Setting setting) {
    String key = setting.key();
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      try {
        return Double.parseDouble(sysProp);
      } catch (NumberFormatException e) {
        // Fall through to provider
      }
    }
    return provider.getDouble(key, setting.defaultValue(Double.class));
  }

  /**
   * Get a boolean setting value.
   * <p>
   * Lookup order: System property → SettingsProvider → Setting default
   *
   * @param setting the setting enum constant
   * @return the setting value
   */
  public synchronized boolean getBoolean(Setting setting) {
    String key = setting.key();
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      return Boolean.parseBoolean(sysProp);
    }
    return provider.getBoolean(key, setting.defaultValue(Boolean.class));
  }

  /**
   * Get a string setting value.
   * <p>
   * Lookup order: System property → SettingsProvider → Setting default
   *
   * @param setting the setting enum constant
   * @return the setting value
   */
  public synchronized String getString(Setting setting) {
    String key = setting.key();
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      return sysProp;
    }
    return provider.getString(key, setting.defaultString());
  }

  /**
   * Get a string setting value with a custom default.
   * <p>
   * Lookup order: System property → SettingsProvider → custom default
   *
   * @param setting       the setting enum constant
   * @param customDefault the custom default to use instead of the Setting's
   *                      default
   * @return the setting value
   */
  public synchronized String getString(Setting setting, String customDefault) {
    String key = setting.key();
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      return sysProp;
    }
    return provider.getString(key, customDefault);
  }

  /**
   * Set an integer setting value.
   *
   * @param setting the setting enum constant
   * @param value   the value to set
   */
  public synchronized void setInt(Setting setting, int value) {
    provider.setInt(setting.key(), value);
  }

  /**
   * Set a boolean setting value.
   *
   * @param setting the setting enum constant
   * @param value   the value to set
   */
  public synchronized void setBoolean(Setting setting, boolean value) {
    provider.setBoolean(setting.key(), value);
  }

  /**
   * Set a string setting value.
   *
   * @param setting the setting enum constant
   * @param value   the value to set
   */
  public synchronized void setString(Setting setting, String value) {
    provider.setString(setting.key(), value);
  }

  // ==================== Backward Compatibility Methods (String Keys)
  // ====================

  /**
   * Get a string setting by key (backward compatibility).
   *
   * @param key          the setting key
   * @param defaultValue the default value
   * @return the setting value
   */
  public String getString(String key, String defaultValue) {
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      return sysProp;
    }
    return provider.getString(key, defaultValue);
  }

  /**
   * Get an integer setting by key (backward compatibility).
   *
   * @param key          the setting key
   * @param defaultValue the default value
   * @return the setting value
   */
  public int getInt(String key, int defaultValue) {
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      try {
        return Integer.parseInt(sysProp);
      } catch (NumberFormatException e) {
        // Fall through to provider
      }
    }
    return provider.getInt(key, defaultValue);
  }

  /**
   * Get a boolean setting by key (backward compatibility).
   *
   * @param key          the setting key
   * @param defaultValue the default value
   * @return the setting value
   */
  public boolean getBoolean(String key, boolean defaultValue) {
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      return Boolean.parseBoolean(sysProp);
    }
    return provider.getBoolean(key, defaultValue);
  }

  /**
   * Get a double setting by key (backward compatibility).
   *
   * @param key          the setting key
   * @param defaultValue the default value
   * @return the setting value
   */
  public double getDouble(String key, double defaultValue) {
    String sysProp = System.getProperty(key);
    if (sysProp != null) {
      try {
        return Double.parseDouble(sysProp);
      } catch (NumberFormatException e) {
        // Fall through to provider
      }
    }
    return provider.getDouble(key, defaultValue);
  }

  /**
   * Get the underlying SettingsProvider (for advanced use cases).
   *
   * @return the wrapped SettingsProvider
   */
  public SettingsProvider getProvider() {
    return provider;
  }
}
