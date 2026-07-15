package com.bitsapplied.descartes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.settings.SettingsProvider;

class MCPServerToolTimeoutTest {

  @Test
  void defaultsToConfiguredToolTimeoutWhenNoOverridesPresent() throws Exception {
    long timeoutMs = resolveToolTimeout(Map.of(), Map.of());
    assertEquals(60_000L, timeoutMs);
  }

  @Test
  void acceptsSnakeCaseTimeoutInToolArguments() throws Exception {
    long timeoutMs = resolveToolTimeout(Map.of("name", "debugger_events"),
        Map.of("operation", "wait", "timeout_ms", 120_000));
    assertEquals(120_000L, timeoutMs);
  }

  @Test
  void acceptsSnakeCaseTimeoutInTopLevelParams() throws Exception {
    long timeoutMs = resolveToolTimeout(Map.of("timeout_ms", "90000"), Map.of());
    assertEquals(90_000L, timeoutMs);
  }

  @Test
  void choosesLargestTimeoutWhenBothCamelAndSnakeCaseAreProvided() throws Exception {
    long timeoutMs = resolveToolTimeout(Map.of("timeoutMs", 35_000), Map.of("timeout_ms", 85_000));
    assertEquals(85_000L, timeoutMs);
  }

  @Test
  void clampsResolvedTimeoutToAllowedRange() throws Exception {
    long minClamped = resolveToolTimeout(Map.of(), Map.of("timeout_ms", 1));
    long maxClamped = resolveToolTimeout(Map.of(), Map.of("timeout_ms", 9_999_999));
    assertEquals(1L, minClamped);
    assertEquals(600_000L, maxClamped);
  }

  @Test
  void givesDebuggerEventWaitTimeToReturnItsSemanticTimeout() throws Exception {
    long timeoutMs = resolveToolExecutionTimeout("debugger_events",
        Map.of("operation", "wait", "timeout_ms", 120_000), 120_000L);

    assertEquals(121_000L, timeoutMs);
  }

  @Test
  void doesNotExtendNonWaitingToolOperations() throws Exception {
    long timeoutMs = resolveToolExecutionTimeout("debugger_events", Map.of("operation", "fetch"), 120_000L);

    assertEquals(120_000L, timeoutMs);
  }

  private long resolveToolTimeout(Map<String, Object> params, Map<String, Object> arguments) throws Exception {
    MCPServer server = new MCPServer(defaultSettingsProvider(), 0);
    try {
      Method method = MCPServer.class.getDeclaredMethod("resolveToolTimeout", Map.class, Map.class);
      method.setAccessible(true);
      return (long) method.invoke(server, params, arguments);
    } finally {
      server.stop();
    }
  }

  private long resolveToolExecutionTimeout(String toolName, Map<String, Object> arguments, long timeoutMs)
      throws Exception {
    MCPServer server = new MCPServer(defaultSettingsProvider(), 0);
    try {
      Method method = MCPServer.class.getDeclaredMethod("resolveToolExecutionTimeout", String.class, Map.class,
          long.class);
      method.setAccessible(true);
      return (long) method.invoke(server, toolName, arguments, timeoutMs);
    } finally {
      server.stop();
    }
  }

  private static SettingsProvider defaultSettingsProvider() {
    return new SettingsProvider() {
      @Override
      public String getString(String key, String defaultValue) {
        return defaultValue;
      }

      @Override
      public int getInt(String key, int defaultValue) {
        return defaultValue;
      }

      @Override
      public boolean getBoolean(String key, boolean defaultValue) {
        return defaultValue;
      }

      @Override
      public double getDouble(String key, double defaultValue) {
        return defaultValue;
      }

      @Override
      public void setString(String key, String value) {
      }

      @Override
      public void setInt(String key, int value) {
      }

      @Override
      public void setBoolean(String key, boolean value) {
      }
    };
  }
}
