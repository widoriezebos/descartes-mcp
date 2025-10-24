package com.bitsapplied.descartes.settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Default settings implementation using Properties file. This can be used when
 * Descartes is used as a standalone MCP server.
 */
public class DefaultSettings implements SettingsProvider {

  private final Properties properties;
  private final File settingsFile;

  public DefaultSettings() {
    this(new File(System.getProperty("user.home"), ".descartes/settings.properties"));
  }

  public DefaultSettings(File settingsFile) {
    this.settingsFile = settingsFile;
    this.properties = new Properties();
    loadSettings();
  }

  private void loadSettings() {
    if (settingsFile.exists()) {
      try (FileInputStream fis = new FileInputStream(settingsFile)) {
        properties.load(fis);
      } catch (IOException e) {
        // Log error or handle appropriately
      }
    }
  }

  private void saveSettings() {
    try {
      settingsFile.getParentFile().mkdirs();
      try (FileOutputStream fos = new FileOutputStream(settingsFile)) {
        properties.store(fos, "Descartes Settings");
      }
    } catch (IOException e) {
      // Log error or handle appropriately
    }
  }

  @Override
  public String getString(String key, String defaultValue) {
    return properties.getProperty(key, defaultValue);
  }

  @Override
  public int getInt(String key, int defaultValue) {
    String value = properties.getProperty(key);
    if (value != null) {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        // Fall through to default
      }
    }
    return defaultValue;
  }

  @Override
  public boolean getBoolean(String key, boolean defaultValue) {
    String value = properties.getProperty(key);
    if (value != null) {
      return Boolean.parseBoolean(value);
    }
    return defaultValue;
  }

  @Override
  public double getDouble(String key, double defaultValue) {
    String value = properties.getProperty(key);
    if (value != null) {
      try {
        return Double.parseDouble(value);
      } catch (NumberFormatException e) {
        // Fall through to default
      }
    }
    return defaultValue;
  }

  @Override
  public void setString(String key, String value) {
    properties.setProperty(key, value);
    saveSettings();
  }

  @Override
  public void setInt(String key, int value) {
    properties.setProperty(key, String.valueOf(value));
    saveSettings();
  }

  @Override
  public void setBoolean(String key, boolean value) {
    properties.setProperty(key, String.valueOf(value));
    saveSettings();
  }
}