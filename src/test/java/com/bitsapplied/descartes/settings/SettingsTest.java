package com.bitsapplied.descartes.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Settings class.
 */
class SettingsTest {

  private SettingsProvider mockProvider;
  private Settings settings;

  @BeforeEach
  void setUp() {
    mockProvider = new MockSettingsProvider();
    settings = new Settings(mockProvider);
  }

  @AfterEach
  void tearDown() {
    // Clear any system properties set during tests
    System.clearProperty("mcp.server.executor.corePoolSize");
    System.clearProperty("jshell.execution.timeout.seconds");
    System.clearProperty("test.boolean.setting");
    System.clearProperty("test.double.setting");
    System.clearProperty("test.string.setting");
  }

  @Test
  void testConstructorWithNullProviderFails() {
    assertThrows(IllegalArgumentException.class, () -> new Settings(null));
  }

  @Test
  void testDefaultConstructor() {
    Settings defaultSettings = new Settings();
    assertNotNull(defaultSettings.getProvider());
    assertTrue(defaultSettings.getProvider() instanceof DefaultSettings);
  }

  @Test
  void testGetIntWithSetting() {
    // Should return default from Setting enum
    int value = settings.getInt(Setting.MCP_EXECUTOR_CORE_POOL_SIZE);
    assertEquals(10, value);
  }

  @Test
  void testGetIntWithSystemProperty() {
    // System property should override
    System.setProperty("mcp.server.executor.corePoolSize", "99");
    int value = settings.getInt(Setting.MCP_EXECUTOR_CORE_POOL_SIZE);
    assertEquals(99, value);
  }

  @Test
  void testGetLongWithSetting() {
    long value = settings.getLong(Setting.THREAD_ANALYZER_MAX_RESPONSE_BYTES);
    assertEquals(200_000L, value);
  }

  @Test
  void testGetBooleanWithBackwardCompat() {
    // Test with backward compat method since we don't have boolean Settings yet
    boolean value = settings.getBoolean("some.bool.key", false);
    assertFalse(value);
  }

  @Test
  void testGetBooleanWithSystemProperty() {
    // Test with actual boolean using backward compat method
    System.setProperty("some.bool.key", "true");
    assertTrue(settings.getBoolean("some.bool.key", false));
  }

  @Test
  void testGetDoubleWithSetting() {
    // Using backward compat since we don't have a double Setting
    double value = settings.getDouble("some.double.key", 3.14);
    assertEquals(3.14, value);
  }

  @Test
  void testGetStringWithSetting() {
    String value = settings.getString(Setting.PROFILER_STORAGE_PATH);
    assertEquals("logs/profiles", value);
  }

  @Test
  void testGetStringWithSystemProperty() {
    System.setProperty("profiler.storage.path", "/custom/path");
    String value = settings.getString(Setting.PROFILER_STORAGE_PATH);
    assertEquals("/custom/path", value);
  }

  @Test
  void testGetStringWithCustomDefault() {
    String value = settings.getString(Setting.PROFILER_STORAGE_PATH, "/override/default");
    // System property takes precedence
    System.setProperty("profiler.storage.path", "/sys/prop");
    value = settings.getString(Setting.PROFILER_STORAGE_PATH, "/override/default");
    assertEquals("/sys/prop", value);
  }

  @Test
  void testSetIntWithSetting() {
    settings.setInt(Setting.MCP_EXECUTOR_CORE_POOL_SIZE, 42);
    // Verify it was set in the provider
    assertEquals(42, mockProvider.getInt("mcp.server.executor.corePoolSize", -1));
  }

  @Test
  void testSetBooleanUsingBackwardCompat() {
    // Test using backward compat since we don't have boolean Settings yet
    mockProvider.setBoolean("some.bool.key", true);
    assertTrue(mockProvider.getBoolean("some.bool.key", false));
  }

  @Test
  void testSetStringWithSetting() {
    settings.setString(Setting.PROFILER_STORAGE_PATH, "/new/path");
    // Verify it was set in the provider
    assertEquals("/new/path", mockProvider.getString("profiler.storage.path", null));
  }

  @Test
  void testBackwardCompatibilityGetString() {
    String value = settings.getString("custom.key", "default");
    assertEquals("default", value);

    // With system property
    System.setProperty("custom.key", "from-sysprop");
    value = settings.getString("custom.key", "default");
    assertEquals("from-sysprop", value);
  }

  @Test
  void testBackwardCompatibilityGetInt() {
    int value = settings.getInt("custom.int.key", 123);
    assertEquals(123, value);

    // With system property
    System.setProperty("custom.int.key", "456");
    value = settings.getInt("custom.int.key", 123);
    assertEquals(456, value);
  }

  @Test
  void testBackwardCompatibilityGetBoolean() {
    boolean value = settings.getBoolean("custom.bool.key", true);
    assertTrue(value);

    // With system property
    System.setProperty("custom.bool.key", "false");
    value = settings.getBoolean("custom.bool.key", true);
    assertFalse(value);
  }

  @Test
  void testBackwardCompatibilityGetDouble() {
    double value = settings.getDouble("custom.double.key", 2.71);
    assertEquals(2.71, value);

    // With system property
    System.setProperty("custom.double.key", "3.14");
    value = settings.getDouble("custom.double.key", 2.71);
    assertEquals(3.14, value, 0.001);
  }

  @Test
  void testSystemPropertyOverrideWithInvalidNumber() {
    System.setProperty("mcp.server.executor.corePoolSize", "not-a-number");
    // Should fall back to provider default
    int value = settings.getInt(Setting.MCP_EXECUTOR_CORE_POOL_SIZE);
    assertEquals(10, value); // Default from Setting enum
  }

  @Test
  void testGetProvider() {
    assertSame(mockProvider, settings.getProvider());
  }

  /**
   * Simple mock SettingsProvider for testing.
   */
  private static class MockSettingsProvider implements SettingsProvider {

    private final Map<String, String> properties = new HashMap<>();

    @Override
    public String getString(String key, String defaultValue) {
      return properties.getOrDefault(key, defaultValue);
    }

    @Override
    public int getInt(String key, int defaultValue) {
      String value = properties.get(key);
      if (value != null) {
        try {
          return Integer.parseInt(value);
        } catch (NumberFormatException e) {
          // Fall through
        }
      }
      return defaultValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
      String value = properties.get(key);
      if (value != null) {
        return Boolean.parseBoolean(value);
      }
      return defaultValue;
    }

    @Override
    public double getDouble(String key, double defaultValue) {
      String value = properties.get(key);
      if (value != null) {
        try {
          return Double.parseDouble(value);
        } catch (NumberFormatException e) {
          // Fall through
        }
      }
      return defaultValue;
    }

    @Override
    public void setString(String key, String value) {
      properties.put(key, value);
    }

    @Override
    public void setInt(String key, int value) {
      properties.put(key, String.valueOf(value));
    }

    @Override
    public void setBoolean(String key, boolean value) {
      properties.put(key, String.valueOf(value));
    }
  }
}
