package com.bitsapplied.descartes;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.settings.Setting;
import com.bitsapplied.descartes.settings.SettingsProvider;

class MCPServerConnectionCapacityTest {

  @Test
  void acceptsNewClientWhenConfiguredCoreHandlersAreIdle() throws Exception {
    int port = findAvailablePort();
    MCPServer server = new MCPServer(new PoolSettings(2, 4, 8, 1), port);

    try {
      server.start();

      try (Socket idleClientOne = openClient(port);
          Socket idleClientTwo = openClient(port);
          Socket activeClient = openClient(port);
          BufferedWriter writer = new BufferedWriter(
              new OutputStreamWriter(activeClient.getOutputStream(), StandardCharsets.UTF_8));
          BufferedReader reader = new BufferedReader(
              new InputStreamReader(activeClient.getInputStream(), StandardCharsets.UTF_8))) {
        activeClient.setSoTimeout(2000);
        writer.write(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"capacity-test\",\"version\":\"1\"}}}");
        writer.write('\n');
        writer.flush();

        String response = reader.readLine();

        assertNotNull(response);
        assertTrue(response.contains("\"id\":1"));
        assertTrue(response.contains("\"protocolVersion\""));
      }
    } finally {
      server.stop();
    }
  }

  private static Socket openClient(int port) throws IOException {
    Socket socket = new Socket("127.0.0.1", port);
    socket.setSoTimeout(2000);
    return socket;
  }

  private static int findAvailablePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static final class PoolSettings implements SettingsProvider {
    private final int corePoolSize;
    private final int maxPoolSize;
    private final int queueCapacity;
    private final int keepAliveSeconds;

    PoolSettings(int corePoolSize, int maxPoolSize, int queueCapacity, int keepAliveSeconds) {
      this.corePoolSize = corePoolSize;
      this.maxPoolSize = maxPoolSize;
      this.queueCapacity = queueCapacity;
      this.keepAliveSeconds = keepAliveSeconds;
    }

    @Override
    public String getString(String key, String defaultValue) {
      return defaultValue;
    }

    @Override
    public int getInt(String key, int defaultValue) {
      if (Setting.MCP_EXECUTOR_CORE_POOL_SIZE.key().equals(key)) {
        return corePoolSize;
      }
      if (Setting.MCP_EXECUTOR_MAX_POOL_SIZE.key().equals(key)) {
        return maxPoolSize;
      }
      if (Setting.MCP_EXECUTOR_QUEUE_CAPACITY.key().equals(key)) {
        return queueCapacity;
      }
      if (Setting.MCP_EXECUTOR_KEEP_ALIVE_SECONDS.key().equals(key)) {
        return keepAliveSeconds;
      }
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
  }
}
