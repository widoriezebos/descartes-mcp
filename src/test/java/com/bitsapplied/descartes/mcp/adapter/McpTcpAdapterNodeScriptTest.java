package com.bitsapplied.descartes.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class McpTcpAdapterNodeScriptTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Path ADAPTER_SCRIPT_PATH = Paths.get("config", "mcp", "mcp-tcp-adapter.js").toAbsolutePath();

  @Test
  void debuggerEventsWaitUsesExtendedTimeoutInNodeAdapter() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    try (var server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<Void> serverFuture = serverExecutor.submit(() -> {
        try (var socket = server.accept();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
          String request = reader.readLine();
          JsonNode requestNode = OBJECT_MAPPER.readTree(request);
          int id = requestNode.get("id").asInt();

          // Delay beyond base adapter timeout so only extended timeout can keep request alive.
          Thread.sleep(650);

          writer.write(String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"ok\":true}}", id));
          writer.write('\n');
          writer.flush();
        }
        return null;
      });

      Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(port), "MCP_REQUEST_TIMEOUT",
          "300", "MCP_RECONNECT_MIN_DELAY", "25", "MCP_RECONNECT_MAX_DELAY", "50");

      try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
        Thread.sleep(200);
        harness.send(
            "{\"jsonrpc\":\"2.0\",\"id\":91,\"method\":\"tools/call\",\"params\":{\"name\":\"descartes/debugger_events\",\"arguments\":{\"operation\":\"wait\",\"timeout_ms\":900,\"types\":[\"debugger.breakpoint_hit\"]}}}");

        String response = harness.readFromAdapter(Duration.ofSeconds(5));
        assertNotNull(response);
        JsonNode node = OBJECT_MAPPER.readTree(response);
        assertEquals(91, node.get("id").asInt());
        assertTrue(node.has("result"), () -> "Expected result response but got: " + node);
        assertThat(node.path("error").isMissingNode())
            .withFailMessage("Expected no error in response, got: %s", node)
            .isTrue();
      } finally {
        serverFuture.get(5, TimeUnit.SECONDS);
        serverExecutor.shutdownNow();
      }
    }
  }

  @Test
  void debuggerEventsWaitTopLevelTimeoutIsInjectedIntoArgumentsInNodeAdapter() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    try (var server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<Void> serverFuture = serverExecutor.submit(() -> {
        try (var socket = server.accept();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
          String request = reader.readLine();
          JsonNode requestNode = OBJECT_MAPPER.readTree(request);
          int id = requestNode.get("id").asInt();

          assertEquals(900, requestNode.path("params").path("timeout_ms").asInt());
          assertEquals(900, requestNode.path("params").path("arguments").path("timeout_ms").asInt());

          // Exceeds base adapter timeout to prove request timeout uses normalized timeout.
          Thread.sleep(650);

          writer.write(String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"ok\":true}}", id));
          writer.write('\n');
          writer.flush();
        }
        return null;
      });

      Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(port), "MCP_REQUEST_TIMEOUT",
          "300", "MCP_RECONNECT_MIN_DELAY", "25", "MCP_RECONNECT_MAX_DELAY", "50");

      try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
        Thread.sleep(200);
        harness.send(
            "{\"jsonrpc\":\"2.0\",\"id\":93,\"method\":\"tools/call\",\"params\":{\"name\":\"debugger_events\",\"timeout_ms\":900,\"arguments\":{\"operation\":\"wait\",\"types\":[\"debugger.breakpoint_hit\"]}}}");

        String response = harness.readFromAdapter(Duration.ofSeconds(5));
        assertNotNull(response);
        JsonNode node = OBJECT_MAPPER.readTree(response);
        assertEquals(93, node.get("id").asInt());
        assertTrue(node.has("result"), () -> "Expected result response but got: " + node);
        assertThat(node.path("error").isMissingNode())
            .withFailMessage("Expected no error in response, got: %s", node)
            .isTrue();
      } finally {
        serverFuture.get(5, TimeUnit.SECONDS);
        serverExecutor.shutdownNow();
      }
    }
  }

  @Test
  void timeoutSecondsExtendsToolsCallDeadlineInNodeAdapter() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    try (var server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<Void> serverFuture = serverExecutor.submit(() -> {
        try (var socket = server.accept();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
          String request = reader.readLine();
          JsonNode requestNode = OBJECT_MAPPER.readTree(request);
          int id = requestNode.get("id").asInt();

          assertEquals(2000, requestNode.path("params").path("timeout_ms").asInt());

          // Exceeds base adapter timeout (300ms) but remains below timeout_seconds budget.
          Thread.sleep(650);

          writer.write(String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"ok\":true}}", id));
          writer.write('\n');
          writer.flush();
        }
        return null;
      });

      Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(port), "MCP_REQUEST_TIMEOUT",
          "300", "MCP_RECONNECT_MIN_DELAY", "25", "MCP_RECONNECT_MAX_DELAY", "50");

      try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
        Thread.sleep(200);
        harness.send(
            "{\"jsonrpc\":\"2.0\",\"id\":94,\"method\":\"tools/call\",\"params\":{\"name\":\"jshell_repl\",\"arguments\":{\"code\":\"1+1\",\"timeout_seconds\":2}}}");

        String response = harness.readFromAdapter(Duration.ofSeconds(5));
        assertNotNull(response);
        JsonNode node = OBJECT_MAPPER.readTree(response);
        assertEquals(94, node.get("id").asInt());
        assertTrue(node.has("result"), () -> "Expected result response but got: " + node);
        assertThat(node.path("error").isMissingNode())
            .withFailMessage("Expected no error in response, got: %s", node)
            .isTrue();
      } finally {
        serverFuture.get(5, TimeUnit.SECONDS);
        serverExecutor.shutdownNow();
      }
    }
  }

  @Test
  void debuggerEventsWaitTimeoutReturnsActionableMessageInNodeAdapter() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    try (var server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<Void> serverFuture = serverExecutor.submit(() -> {
        try (var socket = server.accept();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
          // Consume one request then intentionally never answer to force adapter timeout.
          reader.readLine();
          Thread.sleep(1200);
        }
        return null;
      });

      Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(port), "MCP_REQUEST_TIMEOUT",
          "250", "MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS", "50", "MCP_RECONNECT_MIN_DELAY", "25",
          "MCP_RECONNECT_MAX_DELAY", "50");

      try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
        Thread.sleep(200);
        harness.send(
            "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"tools/call\",\"params\":{\"name\":\"debugger_events\",\"arguments\":{\"operation\":\"wait\",\"timeout_ms\":100,\"types\":[\"debugger.breakpoint_hit\"]}}}");

        String response = harness.readFromAdapter(Duration.ofSeconds(5));
        assertNotNull(response);
        JsonNode node = OBJECT_MAPPER.readTree(response);
        assertEquals(0, node.get("id").asInt());
        assertEquals(-32002, node.get("error").get("code").asInt());
        String message = node.get("error").get("message").asText();
        assertThat(message).contains("Adapter timeout after");
        assertThat(message).contains("debugger_events.wait");
        assertThat(message).contains("since_sequence");
        assertThat(message).contains("MCP_TOOL_TIMEOUT_MS");
      } finally {
        serverFuture.get(5, TimeUnit.SECONDS);
        serverExecutor.shutdownNow();
      }
    }
  }

  @Test
  void debuggerEventsWaitAliasWithNamespacedToolExtendsTimeoutInNodeAdapter() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    try (var server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<Void> serverFuture = serverExecutor.submit(() -> {
        try (var socket = server.accept();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
          String request = reader.readLine();
          JsonNode requestNode = OBJECT_MAPPER.readTree(request);
          int id = requestNode.get("id").asInt();

          // Must exceed base timeout but stay below (wait timeout + grace).
          Thread.sleep(220);

          writer.write(String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"ok\":true}}", id));
          writer.write('\n');
          writer.flush();
        }
        return null;
      });

      Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(port), "MCP_REQUEST_TIMEOUT",
          "150", "MCP_DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS", "75", "MCP_RECONNECT_MIN_DELAY", "25",
          "MCP_RECONNECT_MAX_DELAY", "50");

      try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
        Thread.sleep(200);
        harness.send(
            "{\"jsonrpc\":\"2.0\",\"id\":92,\"method\":\"tools/call\",\"params\":{\"name\":\"descartes.debugger_events\",\"arguments\":{\"operation\":\"wait_for_event\",\"timeout_ms\":200,\"types\":[\"debugger.breakpoint_hit\"]}}}");

        String response = harness.readFromAdapter(Duration.ofSeconds(5));
        JsonNode node = OBJECT_MAPPER.readTree(response);
        assertEquals(92, node.get("id").asInt());
        assertTrue(node.has("result"), () -> "Expected result response but got: " + node);
        assertThat(node.path("error").isMissingNode())
            .withFailMessage("Expected no error in response, got: %s", node)
            .isTrue();
      } finally {
        serverFuture.get(5, TimeUnit.SECONDS);
        serverExecutor.shutdownNow();
      }
    }
  }

  @Test
  void genericRequestTimeoutUsesBaseTimeoutAndPreservesIdZero() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    try (var server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<Void> serverFuture = serverExecutor.submit(() -> {
        try (var socket = server.accept();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
          reader.readLine();
          Thread.sleep(700);
        }
        return null;
      });

      Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(port), "MCP_REQUEST_TIMEOUT",
          "200", "MCP_RECONNECT_MIN_DELAY", "25", "MCP_RECONNECT_MAX_DELAY", "50");

      try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
        Thread.sleep(200);
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"ping\",\"params\":{\"value\":1}}");

        String response = harness.readFromAdapter(Duration.ofSeconds(5));
        JsonNode node = OBJECT_MAPPER.readTree(response);
        assertEquals(0, node.get("id").asInt());
        assertEquals(-32002, node.get("error").get("code").asInt());
        assertEquals("Request timeout after 200ms", node.get("error").get("message").asText());
      } finally {
        serverFuture.get(5, TimeUnit.SECONDS);
        serverExecutor.shutdownNow();
      }
    }
  }

  @Test
  void invalidJsonFromClientReturnsParseError() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    int unusedPort;
    try (var socket = new java.net.ServerSocket(0)) {
      unusedPort = socket.getLocalPort();
    }

    Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(unusedPort), "MCP_REQUEST_TIMEOUT",
        "400", "MCP_RECONNECT_MIN_DELAY", "25", "MCP_RECONNECT_MAX_DELAY", "50");

    try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
      harness.send("{\"jsonrpc\":\"2.0\",\"id\":1");

      String response = harness.readFromAdapter(Duration.ofSeconds(5));
      JsonNode node = OBJECT_MAPPER.readTree(response);
      assertEquals(-32700, node.get("error").get("code").asInt());
      assertThat(node.path("id").isMissingNode()).isTrue();
    }
  }

  @Test
  void queueOverflowDropsOldestQueuedRequestInNodeAdapter() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    int unusedPort;
    try (var socket = new java.net.ServerSocket(0)) {
      unusedPort = socket.getLocalPort();
    }

    Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(unusedPort), "MCP_MESSAGE_QUEUE_SIZE",
        "2", "MCP_REQUEST_TIMEOUT", "2000", "MCP_RECONNECT_MIN_DELAY", "25", "MCP_RECONNECT_MAX_DELAY", "50");

    try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
      harness.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}");
      harness.send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}");
      harness.send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\",\"params\":{}}");

      String response = harness.readFromAdapter(Duration.ofSeconds(5));
      JsonNode node = OBJECT_MAPPER.readTree(response);
      assertEquals(1, node.get("id").asInt());
      assertEquals(-32003, node.get("error").get("code").asInt());
      assertEquals("Message queue full - request dropped", node.get("error").get("message").asText());
    }
  }

  @Test
  void reconnectEmitsCapabilityChangeNotificationsInNodeAdapter() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    try (var server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<Void> serverFuture = serverExecutor.submit(() -> {
        try (var firstSocket = server.accept();
            var firstReader = new BufferedReader(
                new InputStreamReader(firstSocket.getInputStream(), StandardCharsets.UTF_8));
            var firstWriter = new BufferedWriter(
                new OutputStreamWriter(firstSocket.getOutputStream(), StandardCharsets.UTF_8))) {
          String firstRequest = firstReader.readLine();
          JsonNode requestNode = OBJECT_MAPPER.readTree(firstRequest);
          int id = requestNode.get("id").asInt();

          firstWriter.write(String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"ok\":true}}", id));
          firstWriter.write('\n');
          firstWriter.flush();
        }

        try (var secondSocket = server.accept()) {
          Thread.sleep(600);
        }
        return null;
      });

      Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(port), "MCP_REQUEST_TIMEOUT",
          "1200", "MCP_RECONNECT_MIN_DELAY", "30", "MCP_RECONNECT_MAX_DELAY", "60");

      try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
        Thread.sleep(200);
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"ping\",\"params\":{}}");

        // Consume response to the ping request first.
        JsonNode pingResponse = OBJECT_MAPPER.readTree(harness.readFromAdapter(Duration.ofSeconds(5)));
        assertEquals(77, pingResponse.get("id").asInt());
        assertTrue(pingResponse.has("result"));

        Set<String> methods = new HashSet<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (methods.size() < 3 && System.nanoTime() < deadline) {
          String line = harness.pollFromAdapter(Duration.ofMillis(500));
          if (line == null) {
            continue;
          }
          JsonNode node = OBJECT_MAPPER.readTree(line);
          if (node.has("method")) {
            methods.add(node.get("method").asText());
          }
        }

        assertThat(methods).containsExactlyInAnyOrder("notifications/tools/list_changed",
            "notifications/resources/list_changed", "notifications/prompts/list_changed");
      } finally {
        serverFuture.get(5, TimeUnit.SECONDS);
        serverExecutor.shutdownNow();
      }
    }
  }

  @Test
  void pendingRequestFailsWithConnectionLostOnServerDisconnect() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    try (var server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<Void> serverFuture = serverExecutor.submit(() -> {
        try (var socket = server.accept();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
          reader.readLine();
        }
        return null;
      });

      Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(port), "MCP_REQUEST_TIMEOUT",
          "1500", "MCP_RECONNECT_MIN_DELAY", "25", "MCP_RECONNECT_MAX_DELAY", "50");

      try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
        Thread.sleep(200);
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":55,\"method\":\"ping\",\"params\":{}}");

        String response = harness.readFromAdapter(Duration.ofSeconds(5));
        JsonNode node = OBJECT_MAPPER.readTree(response);
        assertEquals(55, node.get("id").asInt());
        assertEquals(-32001, node.get("error").get("code").asInt());
        assertEquals("Connection to MCP server lost", node.get("error").get("message").asText());
      } finally {
        serverFuture.get(5, TimeUnit.SECONDS);
        serverExecutor.shutdownNow();
      }
    }
  }

  @Test
  void invalidServerMessageIsIgnoredAndAdapterContinues() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    try (var server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<Void> serverFuture = serverExecutor.submit(() -> {
        try (var socket = server.accept();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
          writer.write("not-json\n");
          writer.flush();

          String request = reader.readLine();
          JsonNode requestNode = OBJECT_MAPPER.readTree(request);
          int id = requestNode.get("id").asInt();

          writer.write(String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"ok\":true}}", id));
          writer.write('\n');
          writer.flush();
        }
        return null;
      });

      Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(port), "MCP_REQUEST_TIMEOUT",
          "1200", "MCP_RECONNECT_MIN_DELAY", "25", "MCP_RECONNECT_MAX_DELAY", "50");

      try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
        Thread.sleep(200);
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":301,\"method\":\"ping\",\"params\":{}}");

        String response = harness.readFromAdapter(Duration.ofSeconds(5));
        JsonNode node = OBJECT_MAPPER.readTree(response);
        assertEquals(301, node.get("id").asInt());
        assertTrue(node.has("result"), () -> "Expected valid response after invalid server message, got: " + node);
      } finally {
        serverFuture.get(5, TimeUnit.SECONDS);
        serverExecutor.shutdownNow();
      }
    }
  }

  @Test
  void receiveBufferOverflowClearsBufferAndContinues() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    try (var server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
      Future<Void> serverFuture = serverExecutor.submit(() -> {
        try (var socket = server.accept();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
          writer.write("X".repeat(200));
          writer.flush();
          Thread.sleep(100);

          String request = reader.readLine();
          JsonNode requestNode = OBJECT_MAPPER.readTree(request);
          int id = requestNode.get("id").asInt();

          writer.write(String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"ok\":true}}", id));
          writer.write('\n');
          writer.flush();
        }
        return null;
      });

      Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(port), "MCP_MAX_MESSAGE_SIZE",
          "64", "MCP_REQUEST_TIMEOUT", "1200", "MCP_RECONNECT_MIN_DELAY", "25", "MCP_RECONNECT_MAX_DELAY", "50");

      try (NodeAdapterHarness harness = new NodeAdapterHarness(env)) {
        Thread.sleep(250);
        harness.send("{\"jsonrpc\":\"2.0\",\"id\":302,\"method\":\"ping\",\"params\":{}}");

        String response = harness.readFromAdapter(Duration.ofSeconds(5));
        JsonNode node = OBJECT_MAPPER.readTree(response);
        assertEquals(302, node.get("id").asInt());
        assertTrue(node.has("result"), () -> "Expected valid response after receive-buffer overflow, got: " + node);
      } finally {
        serverFuture.get(5, TimeUnit.SECONDS);
        serverExecutor.shutdownNow();
      }
    }
  }

  @Test
  void stdinCloseTriggersGracefulShutdownWithoutHang() throws Exception {
    Assumptions.assumeTrue(isNodeAvailable(), "node is not available on PATH");

    int unusedPort;
    try (var socket = new java.net.ServerSocket(0)) {
      unusedPort = socket.getLocalPort();
    }

    Map<String, String> env = Map.of("MCP_HOST", "localhost", "MCP_PORT", Integer.toString(unusedPort), "MCP_REQUEST_TIMEOUT",
        "1200", "MCP_RECONNECT_MIN_DELAY", "25", "MCP_RECONNECT_MAX_DELAY", "50");

    NodeAdapterHarness harness = new NodeAdapterHarness(env);
    try {
      Thread.sleep(200);
      harness.closeStdin();
      int exitCode = harness.waitForExit(Duration.ofSeconds(2));
      assertEquals(0, exitCode);
    } finally {
      harness.close();
    }
  }

  private static boolean isNodeAvailable() {
    try {
      Process process = new ProcessBuilder("node", "--version").start();
      boolean exited = process.waitFor(2, TimeUnit.SECONDS);
      return exited && process.exitValue() == 0;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static final class NodeAdapterHarness implements AutoCloseable {
    private final Process process;
    private final BufferedWriter inputWriter;
    private final BufferedReader outputReader;
    private final BufferedReader errorReader;
    private final ExecutorService ioExecutor;
    private final List<String> errorLines = new ArrayList<>();
    private final Future<?> errorPump;
    private volatile boolean closed;

    NodeAdapterHarness(Map<String, String> envOverrides) throws IOException {
      ProcessBuilder builder = new ProcessBuilder("node", ADAPTER_SCRIPT_PATH.toString());
      builder.directory(Paths.get(".").toAbsolutePath().toFile());
      builder.environment().putAll(envOverrides);
      this.process = builder.start();
      this.inputWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      this.outputReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      this.errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
      this.ioExecutor = Executors.newCachedThreadPool();
      this.errorPump = ioExecutor.submit(() -> {
        try {
          String line;
          while ((line = errorReader.readLine()) != null) {
            synchronized (errorLines) {
              errorLines.add(line);
            }
          }
        } catch (IOException ignored) {
          // Stream closed during shutdown.
        }
      });
    }

    void send(String message) {
      try {
        synchronized (inputWriter) {
          inputWriter.write(message);
          inputWriter.write('\n');
          inputWriter.flush();
        }
      } catch (IOException e) {
        throw new IllegalStateException("Failed to write request to Node adapter", e);
      }
    }

    void closeStdin() {
      try {
        synchronized (inputWriter) {
          inputWriter.close();
        }
      } catch (IOException ignored) {
      }
    }

    int waitForExit(Duration timeout) throws InterruptedException {
      boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        throw new AssertionError("Node adapter did not exit within " + timeout + ". stderr:\n" + stderrSnapshot());
      }
      return process.exitValue();
    }

    String readFromAdapter(Duration timeout) throws Exception {
      Future<String> future = ioExecutor.submit(() -> {
        try {
          return outputReader.readLine();
        } catch (IOException e) {
          throw new IllegalStateException("Failed to read response from Node adapter", e);
        }
      });
      try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        future.cancel(true);
        throw new AssertionError("Timed out waiting for adapter output. stderr:\n" + stderrSnapshot(), e);
      }
    }

    String pollFromAdapter(Duration timeout) throws Exception {
      Future<String> future = ioExecutor.submit(() -> {
        try {
          return outputReader.readLine();
        } catch (IOException e) {
          throw new IllegalStateException("Failed to read response from Node adapter", e);
        }
      });
      try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        future.cancel(true);
        return null;
      }
    }

    private String stderrSnapshot() {
      synchronized (errorLines) {
        return String.join("\n", errorLines);
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      closeStdin();
      process.destroy();
      try {
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(2, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      try {
        outputReader.close();
      } catch (IOException ignored) {
      }
      try {
        errorReader.close();
      } catch (IOException ignored) {
      }
      errorPump.cancel(true);
      ioExecutor.shutdownNow();
    }
  }
}
