package com.bitsapplied.descartes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.integration.MCPEventBridge.DebuggerNotification;
import com.bitsapplied.descartes.mcp.MCPNotificationDispatcher;
import com.bitsapplied.descartes.settings.SettingsProvider;

class MCPServerNotificationTest {

  private MCPNotificationDispatcher dispatcherOne;
  private MCPNotificationDispatcher dispatcherTwo;

  @AfterEach
  void tearDown() throws Exception {
    if (dispatcherOne != null) {
      dispatcherOne.close();
    }
    if (dispatcherTwo != null) {
      dispatcherTwo.close();
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  void broadcastsDebuggerNotificationsToAllActiveDispatchers() throws Exception {
    SettingsProvider settings = new SettingsProvider() {
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

    MCPServer server = new MCPServer(settings, 0);

    Field activeDispatchersField = MCPServer.class.getDeclaredField("activeDispatchers");
    activeDispatchersField.setAccessible(true);
    Set<MCPNotificationDispatcher> activeDispatchers = (Set<MCPNotificationDispatcher>) activeDispatchersField
        .get(server);

    ByteArrayOutputStream bufferOne = new ByteArrayOutputStream();
    ByteArrayOutputStream bufferTwo = new ByteArrayOutputStream();
    Object lockOne = new Object();
    Object lockTwo = new Object();

    dispatcherOne = new MCPNotificationDispatcher(bufferOne, lockOne);
    dispatcherTwo = new MCPNotificationDispatcher(bufferTwo, lockTwo);
    activeDispatchers.add(dispatcherOne);
    activeDispatchers.add(dispatcherTwo);

    Method handleNotification = MCPServer.class.getDeclaredMethod("handleDebuggerNotification",
        DebuggerNotification.class);
    handleNotification.setAccessible(true);

    DebuggerNotification notification = new DebuggerNotification("debugger.test", Map.of("data", Map.of("value", 42)));

    handleNotification.invoke(server, notification);

    // Allow dispatcher threads to process the queue
    Thread.sleep(200);

    String outputOne = bufferOne.toString(StandardCharsets.UTF_8);
    String outputTwo = bufferTwo.toString(StandardCharsets.UTF_8);

    assertTrue(outputOne.contains("\"method\":\"notifications/debugger\""));
    assertTrue(outputTwo.contains("\"method\":\"notifications/debugger\""));
    assertTrue(outputOne.contains("\"value\":42"));
    assertTrue(outputTwo.contains("\"value\":42"));
  }
}
