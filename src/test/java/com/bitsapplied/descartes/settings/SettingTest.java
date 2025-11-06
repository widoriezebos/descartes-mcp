package com.bitsapplied.descartes.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Tests for the Setting enum.
 */
class SettingTest {

  @Test
  void testEnumValues() {
    // Verify some key settings exist
    assertNotNull(Setting.MCP_EXECUTOR_CORE_POOL_SIZE);
    assertNotNull(Setting.JSHELL_EXECUTION_TIMEOUT_SECONDS);
    assertNotNull(Setting.THREAD_ANALYZER_MAX_RESPONSE_BYTES);
    assertNotNull(Setting.DEBUGGER_SHUTDOWN_TIMEOUT_SECONDS);
    assertNotNull(Setting.PROFILER_DURATION_MIN_SECONDS);
  }

  @Test
  void testKey() {
    assertEquals("mcp.server.executor.corePoolSize", Setting.MCP_EXECUTOR_CORE_POOL_SIZE.key());
    assertEquals("jshell.execution.timeout.seconds", Setting.JSHELL_EXECUTION_TIMEOUT_SECONDS.key());
    assertEquals("thread.analyzer.maxResponseBytes", Setting.THREAD_ANALYZER_MAX_RESPONSE_BYTES.key());
  }

  @Test
  void testDefaultValue() {
    assertEquals(10, Setting.MCP_EXECUTOR_CORE_POOL_SIZE.defaultValue());
    assertEquals(30, Setting.JSHELL_EXECUTION_TIMEOUT_SECONDS.defaultValue());
    assertEquals(200_000, Setting.THREAD_ANALYZER_MAX_RESPONSE_BYTES.defaultValue());
    assertEquals(10, Setting.DEBUGGER_SHUTDOWN_TIMEOUT_SECONDS.defaultValue());
  }

  @Test
  void testDefaultString() {
    assertEquals("10", Setting.MCP_EXECUTOR_CORE_POOL_SIZE.defaultString());
    assertEquals("30", Setting.JSHELL_EXECUTION_TIMEOUT_SECONDS.defaultString());
    assertEquals("10485760", Setting.MCP_MESSAGE_MAX_SIZE_BYTES.defaultString());
  }

  @Test
  void testDefaultValueWithType() {
    assertEquals(Integer.valueOf(10), Setting.MCP_EXECUTOR_CORE_POOL_SIZE.defaultValue(Integer.class));
    assertEquals(Integer.valueOf(30), Setting.JSHELL_EXECUTION_TIMEOUT_SECONDS.defaultValue(Integer.class));

    // Test integer values (THREAD_ANALYZER_MAX_RESPONSE_BYTES is actually stored as
    // Integer)
    assertEquals(Integer.valueOf(200_000), Setting.THREAD_ANALYZER_MAX_RESPONSE_BYTES.defaultValue(Integer.class));
  }

  @Test
  void testDefaultValueWithWrongTypeFails() {
    assertThrows(ClassCastException.class, () -> {
      Setting.MCP_EXECUTOR_CORE_POOL_SIZE.defaultValue(String.class);
    });
  }

  @Test
  void testFromKey() {
    assertEquals(Setting.MCP_EXECUTOR_CORE_POOL_SIZE, Setting.fromKey("mcp.server.executor.corePoolSize"));
    assertEquals(Setting.JSHELL_EXECUTION_TIMEOUT_SECONDS, Setting.fromKey("jshell.execution.timeout.seconds"));
    assertNull(Setting.fromKey("non.existent.key"));
  }

  @Test
  void testDefaultFor() {
    assertEquals(Integer.valueOf(10), Setting.defaultFor("mcp.server.executor.corePoolSize", Integer.class));
    assertEquals(Integer.valueOf(30), Setting.defaultFor("jshell.execution.timeout.seconds", Integer.class));
    assertNull(Setting.defaultFor("non.existent.key", Integer.class));
  }

  @Test
  void testAllKeysAreUnique() {
    // Verify no duplicate keys
    long uniqueKeys = Arrays.stream(Setting.values()).map(Setting::key).distinct().count();
    assertEquals(Setting.values().length, uniqueKeys, "All Setting keys must be unique");
  }

  @Test
  void testAllDefaultValuesAreNonNull() {
    // Verify all settings have non-null default values
    for (Setting setting : Setting.values()) {
      assertNotNull(setting.defaultValue(), "Setting " + setting.name() + " has null default value");
    }
  }

  @Test
  void testIntegerDefaults() {
    // Test various integer settings
    assertTrue(Setting.MCP_EXECUTOR_CORE_POOL_SIZE.defaultValue() instanceof Integer);
    assertTrue(Setting.MCP_EXECUTOR_MAX_POOL_SIZE.defaultValue() instanceof Integer);
    assertTrue(Setting.JSHELL_EXECUTION_TIMEOUT_SECONDS.defaultValue() instanceof Integer);

    // Verify reasonable values
    assertTrue((Integer) Setting.MCP_EXECUTOR_CORE_POOL_SIZE.defaultValue() > 0);
    assertTrue((Integer) Setting.MCP_EXECUTOR_MAX_POOL_SIZE.defaultValue() > 0);
  }

  @Test
  void testStringDefaults() {
    // Test string settings
    assertTrue(Setting.PROFILER_STORAGE_PATH.defaultValue() instanceof String);
    assertEquals("logs/profiles", Setting.PROFILER_STORAGE_PATH.defaultValue());
  }

  @Test
  void testKeyNamingConventions() {
    // Verify keys follow dot notation conventions
    for (Setting setting : Setting.values()) {
      String key = setting.key();
      assertTrue(key.contains("."), "Setting key should contain dots: " + key);
      assertFalse(key.startsWith("."), "Setting key should not start with dot: " + key);
      assertFalse(key.endsWith("."), "Setting key should not end with dot: " + key);
    }
  }
}
