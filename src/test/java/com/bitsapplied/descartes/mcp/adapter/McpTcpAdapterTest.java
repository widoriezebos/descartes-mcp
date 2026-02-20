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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Timeout(30)
final class McpTcpAdapterTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void initializeRequestResentAfterReconnect() throws Exception {
    ServerSocket serverSocket = new ServerSocket(0);
    int port = serverSocket.getLocalPort();
    AdapterConfig config = AdapterConfig.builder().host("localhost").port(port).debug(false).reconnectMinDelayMs(100)
        .reconnectMaxDelayMs(400).messageQueueSize(16).requestTimeoutMs(4000).tcpKeepAliveDelayMs(1000)
        .logRateLimitWindowMs(2000).logRateLimitMax(100).maxMessageSizeBytes(1_048_576).build();

    String initializeRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    String initializeResponse = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}";

    serverSocket.setSoTimeout(5000);
    try (AdapterTestHarness harness = new AdapterTestHarness(config)) {
      Thread.sleep(350);
      try (Socket client = serverSocket.accept();
          BufferedReader reader = new BufferedReader(
              new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
          BufferedWriter writer = new BufferedWriter(
              new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
        harness.sendToAdapter(initializeRequest);

        String received = reader.readLine();
        assertEquals(initializeRequest, received);

        writer.write(initializeResponse);
        writer.write('\n');
        writer.flush();
        Thread.sleep(200);

        String clientResponse = harness.readFromAdapter(Duration.ofSeconds(5));
        assertEquals(initializeResponse, clientResponse);
      }
    } finally {
      closeQuietly(serverSocket);
    }
  }

  @Test
  void reconnectionEmitsCapabilityNotifications() throws Exception {
    ServerSocket serverSocket = new ServerSocket(0);
    int port = serverSocket.getLocalPort();
    AdapterConfig config = AdapterConfig.builder().host("localhost").port(port).debug(false).reconnectMinDelayMs(100)
        .reconnectMaxDelayMs(200).messageQueueSize(16).requestTimeoutMs(3000).tcpKeepAliveDelayMs(1000)
        .logRateLimitWindowMs(2000).logRateLimitMax(100).maxMessageSizeBytes(1_048_576).build();

    CountDownLatch firstConnection = new CountDownLatch(1);
    CountDownLatch secondConnection = new CountDownLatch(1);

    ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    serverExecutor.submit(() -> {
      try {
        try (Socket _conn1 = serverSocket.accept()) {
          firstConnection.countDown();
          Thread.sleep(250);
        }
        try (Socket _conn2 = serverSocket.accept()) {
          secondConnection.countDown();
          Thread.sleep(250);
        }
        return null;
      } finally {
        closeQuietly(serverSocket);
      }
    });

    try (AdapterTestHarness harness = new AdapterTestHarness(config)) {
      assertTrue(firstConnection.await(2, TimeUnit.SECONDS));
      assertTrue(secondConnection.await(5, TimeUnit.SECONDS));

      Set<String> notifications = new HashSet<>();
      notifications.add(harness.readFromAdapter(Duration.ofSeconds(5)));
      notifications.add(harness.readFromAdapter(Duration.ofSeconds(5)));
      notifications.add(harness.readFromAdapter(Duration.ofSeconds(5)));

      assertThat(notifications).containsExactlyInAnyOrder(
          "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\",\"params\":{}}",
          "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/resources/list_changed\",\"params\":{}}",
          "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/prompts/list_changed\",\"params\":{}}");
    } finally {
      serverExecutor.shutdownNow();
      closeQuietly(serverSocket);
    }
  }

  @Test
  void queueOverflowDropsOldestRequest() throws Exception {
    int port = findFreePort();
    AdapterConfig config = AdapterConfig.builder().host("localhost").port(port).debug(false).reconnectMinDelayMs(100)
        .reconnectMaxDelayMs(200).messageQueueSize(2).requestTimeoutMs(4000).tcpKeepAliveDelayMs(1000)
        .logRateLimitWindowMs(2000).logRateLimitMax(100).maxMessageSizeBytes(1_048_576).build();

    try (AdapterTestHarness harness = new AdapterTestHarness(config)) {
      harness.sendToAdapter(requestWithId(1));
      harness.sendToAdapter(requestWithId(2));
      harness.sendToAdapter(requestWithId(3));

      String response = harness.readFromAdapter(Duration.ofSeconds(5));
      assertNotNull(response);
      JsonNode node = OBJECT_MAPPER.readTree(response);
      assertEquals(1, node.get("id").asInt());
      assertEquals(-32003, node.get("error").get("code").asInt());
    }
  }

  @Test
  void requestTimeoutEmitsError() throws Exception {
    int port = findFreePort();
    AdapterConfig config = AdapterConfig.builder().host("localhost").port(port).debug(false).reconnectMinDelayMs(50)
        .reconnectMaxDelayMs(100).messageQueueSize(16).requestTimeoutMs(350).tcpKeepAliveDelayMs(1000)
        .logRateLimitWindowMs(2000).logRateLimitMax(100).maxMessageSizeBytes(1_048_576).build();

    try (AdapterTestHarness harness = new AdapterTestHarness(config)) {
      harness.sendToAdapter(requestWithId(42));
      String response = harness.readFromAdapter(Duration.ofSeconds(5));
      assertNotNull(response);
      JsonNode node = OBJECT_MAPPER.readTree(response);
      assertEquals(42, node.get("id").asInt());
      assertEquals(-32002, node.get("error").get("code").asInt());
    }
  }

  @Test
  void debuggerEventsWaitExtendsTimeoutBeyondAdapterDefault() throws Exception {
    assertDebuggerEventsWaitTimeoutExtended("debugger_events", "wait");
  }

  @Test
  void debuggerEventsWaitExtendsTimeoutForNamespacedToolNames() throws Exception {
    assertDebuggerEventsWaitTimeoutExtended("descartes.debugger_events", "wait");
    assertDebuggerEventsWaitTimeoutExtended("descartes/debugger_events", "wait");
  }

  @Test
  void debuggerEventsWaitAliasesExtendTimeoutBeyondAdapterDefault() throws Exception {
    assertDebuggerEventsWaitTimeoutExtended("debugger_events", "wait_for");
    assertDebuggerEventsWaitTimeoutExtended("descartes.debugger_events", "wait_for_event");
  }

  @Test
  void debuggerEventsWaitTopLevelTimeoutIsInjectedIntoArguments() throws Exception {
    ServerSocket serverSocket = new ServerSocket(0);
    int port = serverSocket.getLocalPort();
    AdapterConfig config = AdapterConfig.builder().host("localhost").port(port).debug(false).reconnectMinDelayMs(50)
        .reconnectMaxDelayMs(100).messageQueueSize(16).requestTimeoutMs(300).tcpKeepAliveDelayMs(1000)
        .logRateLimitWindowMs(2000).logRateLimitMax(100).maxMessageSizeBytes(1_048_576).build();

    CountDownLatch connectedLatch = new CountDownLatch(1);
    ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    Future<Void> serverFuture = serverExecutor.submit(() -> {
      try (Socket socket = serverSocket.accept();
          BufferedReader reader = new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
          BufferedWriter writer = new BufferedWriter(
              new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
        connectedLatch.countDown();
        String request = reader.readLine();
        JsonNode requestNode = OBJECT_MAPPER.readTree(request);
        int id = requestNode.get("id").asInt();

        assertEquals(900, requestNode.path("params").path("timeout_ms").asInt());
        assertEquals(900, requestNode.path("params").path("arguments").path("timeout_ms").asInt());

        // Exceeds base adapter timeout to prove normalized timeout is used.
        Thread.sleep(650);

        writer.write(String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"ok\":true}}", id));
        writer.write('\n');
        writer.flush();
      }
      return null;
    });

    String waitRequest =
        "{\"jsonrpc\":\"2.0\",\"id\":95,\"method\":\"tools/call\",\"params\":{\"name\":\"debugger_events\",\"timeout_ms\":900,\"arguments\":{\"operation\":\"wait\",\"types\":[\"debugger.breakpoint_hit\"]}}}";

    try (AdapterTestHarness harness = new AdapterTestHarness(config)) {
      assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Adapter failed to connect");
      harness.sendToAdapter(waitRequest);
      String response = harness.readFromAdapter(Duration.ofSeconds(5));
      assertNotNull(response);

      JsonNode node = OBJECT_MAPPER.readTree(response);
      assertEquals(95, node.get("id").asInt());
      assertTrue(node.has("result"));
      assertThat(node.path("error").isMissingNode()).isTrue();
    } finally {
      serverFuture.get(5, TimeUnit.SECONDS);
      serverExecutor.shutdownNow();
      closeQuietly(serverSocket);
    }
  }

  @Test
  void timeoutSecondsExtendsToolsCallDeadline() throws Exception {
    ServerSocket serverSocket = new ServerSocket(0);
    int port = serverSocket.getLocalPort();
    AdapterConfig config = AdapterConfig.builder().host("localhost").port(port).debug(false).reconnectMinDelayMs(50)
        .reconnectMaxDelayMs(100).messageQueueSize(16).requestTimeoutMs(300).tcpKeepAliveDelayMs(1000)
        .logRateLimitWindowMs(2000).logRateLimitMax(100).maxMessageSizeBytes(1_048_576).build();

    CountDownLatch connectedLatch = new CountDownLatch(1);
    ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    Future<Void> serverFuture = serverExecutor.submit(() -> {
      try (Socket socket = serverSocket.accept();
          BufferedReader reader = new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
          BufferedWriter writer = new BufferedWriter(
              new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
        connectedLatch.countDown();
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

    String request =
        "{\"jsonrpc\":\"2.0\",\"id\":96,\"method\":\"tools/call\",\"params\":{\"name\":\"jshell_repl\",\"arguments\":{\"code\":\"1+1\",\"timeout_seconds\":2}}}";

    try (AdapterTestHarness harness = new AdapterTestHarness(config)) {
      assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Adapter failed to connect");
      harness.sendToAdapter(request);
      String response = harness.readFromAdapter(Duration.ofSeconds(5));
      assertNotNull(response);

      JsonNode node = OBJECT_MAPPER.readTree(response);
      assertEquals(96, node.get("id").asInt());
      assertTrue(node.has("result"));
      assertThat(node.path("error").isMissingNode()).isTrue();
    } finally {
      serverFuture.get(5, TimeUnit.SECONDS);
      serverExecutor.shutdownNow();
      closeQuietly(serverSocket);
    }
  }

  private void assertDebuggerEventsWaitTimeoutExtended(String toolName, String operation) throws Exception {
    ServerSocket serverSocket = new ServerSocket(0);
    int port = serverSocket.getLocalPort();
    AdapterConfig config = AdapterConfig.builder().host("localhost").port(port).debug(false).reconnectMinDelayMs(50)
        .reconnectMaxDelayMs(100).messageQueueSize(16).requestTimeoutMs(300).tcpKeepAliveDelayMs(1000)
        .logRateLimitWindowMs(2000).logRateLimitMax(100).maxMessageSizeBytes(1_048_576).build();

    CountDownLatch connectedLatch = new CountDownLatch(1);
    ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    Future<Void> serverFuture = serverExecutor.submit(() -> {
      try (Socket socket = serverSocket.accept();
          BufferedReader reader = new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
          BufferedWriter writer = new BufferedWriter(
              new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
        connectedLatch.countDown();
        String request = reader.readLine();
        JsonNode requestNode = OBJECT_MAPPER.readTree(request);
        int id = requestNode.get("id").asInt();

        // Delay longer than adapter requestTimeoutMs (300ms) but shorter than debugger wait timeout.
        Thread.sleep(650);

        writer.write(String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"ok\":true}}", id));
        writer.write('\n');
        writer.flush();
      }
      return null;
    });

    String waitRequest = String.format(
        "{\"jsonrpc\":\"2.0\",\"id\":91,\"method\":\"tools/call\",\"params\":{\"name\":\"%s\",\"arguments\":{\"operation\":\"%s\",\"timeout_ms\":900,\"types\":[\"debugger.breakpoint_hit\"]}}}",
        toolName, operation);

    try (AdapterTestHarness harness = new AdapterTestHarness(config)) {
      assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Adapter failed to connect");
      harness.sendToAdapter(waitRequest);
      String response = harness.readFromAdapter(Duration.ofSeconds(5));
      assertNotNull(response);

      JsonNode node = OBJECT_MAPPER.readTree(response);
      assertEquals(91, node.get("id").asInt());
      assertTrue(node.has("result"));
      assertThat(node.path("error").isMissingNode()).isTrue();
    } finally {
      serverFuture.get(5, TimeUnit.SECONDS);
      serverExecutor.shutdownNow();
      closeQuietly(serverSocket);
    }
  }

  @Test
  void invalidJsonProducesParseError() throws Exception {
    int port = findFreePort();
    AdapterConfig config = AdapterConfig.builder().host("localhost").port(port).debug(false).reconnectMinDelayMs(100)
        .reconnectMaxDelayMs(200).messageQueueSize(16).requestTimeoutMs(4000).tcpKeepAliveDelayMs(1000)
        .logRateLimitWindowMs(2000).logRateLimitMax(100).maxMessageSizeBytes(1_048_576).build();

    try (AdapterTestHarness harness = new AdapterTestHarness(config)) {
      harness.sendToAdapter("{\"jsonrpc\":\"1.0\",\"id\":7}");
      String response = harness.readFromAdapter(Duration.ofSeconds(5));
      assertNotNull(response);
      JsonNode node = OBJECT_MAPPER.readTree(response);
      assertTrue(node.has("id") && node.get("id").isNull());
      assertEquals(-32700, node.get("error").get("code").asInt());
    }
  }

  @Test
  void pendingRequestsFailWhenConnectionLost() throws Exception {
    int port = findFreePort();
    AdapterConfig config = AdapterConfig.builder().host("localhost").port(port).debug(false).reconnectMinDelayMs(100)
        .reconnectMaxDelayMs(200).messageQueueSize(16).requestTimeoutMs(4000).tcpKeepAliveDelayMs(1000)
        .logRateLimitWindowMs(2000).logRateLimitMax(100).maxMessageSizeBytes(1_048_576).build();

    ExecutorService serverExecutor = Executors.newSingleThreadExecutor();

    CompletableFuture<Void> server = CompletableFuture.runAsync(() -> {
      try (ServerSocket serverSocket = new ServerSocket(port);
          Socket socket = serverSocket.accept();
          BufferedReader reader = new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        reader.readLine();
      } catch (IOException e) {
        throw new CompletionException(e);
      }
    }, serverExecutor);

    try (AdapterTestHarness harness = new AdapterTestHarness(config)) {
      harness.sendToAdapter(requestWithId(55));
      server.get(5, TimeUnit.SECONDS);
      String response = harness.readFromAdapter(Duration.ofSeconds(5));
      assertNotNull(response);
      JsonNode node = OBJECT_MAPPER.readTree(response);
      assertEquals(55, node.get("id").asInt());
      assertEquals(-32001, node.get("error").get("code").asInt());
    } finally {
      serverExecutor.shutdownNow();
    }
  }

  @Test
  void concurrentRequestsRoundTripSuccessfully() throws Exception {
    ServerSocket serverSocket = new ServerSocket(0);
    int port = serverSocket.getLocalPort();
    AdapterConfig config = AdapterConfig.builder().host("localhost").port(port).debug(false).reconnectMinDelayMs(100)
        .reconnectMaxDelayMs(200).messageQueueSize(16).requestTimeoutMs(4000).tcpKeepAliveDelayMs(1000)
        .logRateLimitWindowMs(2000).logRateLimitMax(100).maxMessageSizeBytes(1_048_576).build();

    ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    CountDownLatch serverCloseLatch = new CountDownLatch(1);
    CountDownLatch connectedLatch = new CountDownLatch(1);
    Future<List<String>> serverFuture = serverExecutor
        .submit(() -> serveEchoRequests(serverSocket, 6, serverCloseLatch, connectedLatch));

    try (AdapterTestHarness harness = new AdapterTestHarness(config)) {
      assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Adapter failed to connect to echo server");
      ExecutorService senderPool = Executors.newFixedThreadPool(3);
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        final int id = i + 1;
        tasks.add(() -> {
          harness.sendToAdapter(requestWithId(id));
          return null;
        });
      }
      senderPool.invokeAll(tasks);
      senderPool.shutdown();
      senderPool.awaitTermination(5, TimeUnit.SECONDS);

      List<String> responses = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        String response = harness.readFromAdapter(Duration.ofSeconds(10));
        if (response == null) {
          break;
        }
        responses.add(response);
      }

      serverCloseLatch.countDown();
      List<String> receivedByServer = serverFuture.get(5, TimeUnit.SECONDS);
      assertEquals(6, receivedByServer.size(),
          () -> "Server received " + receivedByServer.size() + " messages: " + receivedByServer);
      assertEquals(6, responses.size(), () -> "Adapter forwarded only " + responses.size() + " responses: " + responses
          + "; server saw " + receivedByServer);

      Set<Integer> responseIds = new HashSet<>();
      for (String response : responses) {
        JsonNode node = OBJECT_MAPPER.readTree(response);
        responseIds.add(node.get("id").asInt());
        JsonNode resultNode = node.get("result");
        assertNotNull(resultNode);
        assertEquals(node.get("id").asInt(), resultNode.get("echo").asInt());
      }
      assertThat(responseIds).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
    } finally {
      serverExecutor.shutdownNow();
      closeQuietly(serverSocket);
    }
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static String requestWithId(int id) {
    return String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"ping\",\"params\":{\"seed\":%d}}", id,
        ThreadLocalRandom.current().nextInt());
  }

  private static List<String> serveEchoRequests(ServerSocket serverSocket, int count, CountDownLatch closeLatch,
      CountDownLatch connectedLatch) throws Exception {
    List<String> received = new ArrayList<>();
    try (Socket socket = serverSocket.accept();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
      connectedLatch.countDown();
      for (int i = 0; i < count; i++) {
        String message = reader.readLine();
        received.add(message);
        JsonNode node = OBJECT_MAPPER.readTree(message);
        int id = node.get("id").asInt();
        String response = String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":{\"echo\":%d}}", id, id);
        writer.write(response);
        writer.write('\n');
        writer.flush();
      }
      closeLatch.await(5, TimeUnit.SECONDS);
    }
    return received;
  }

  private static void closeQuietly(ServerSocket socket) {
    if (socket == null) {
      return;
    }
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }

  private static final class AdapterTestHarness implements AutoCloseable {
    private final ScheduledExecutorService scheduler;
    private final RateLimitedLogger logger;
    private final JsonRpcValidator validator;
    private final McpTcpAdapter adapter;
    private final BufferedWriter inputWriter;
    private final BufferedReader outputReader;
    private final ExecutorService ioExecutor;
    private final Thread adapterThread;
    private final PipedInputStream adapterInput;
    private final PipedOutputStream adapterOutput;
    private volatile boolean closed;

    @SuppressWarnings("resource")
    AdapterTestHarness(AdapterConfig config) throws IOException {
      this.scheduler = Executors.newScheduledThreadPool(4);
      this.logger = new RateLimitedLogger(config, scheduler);
      this.validator = new JsonRpcValidator(OBJECT_MAPPER, config.maxMessageSizeBytes);
      this.adapter = new McpTcpAdapter(config, logger, validator, OBJECT_MAPPER, scheduler);
      this.adapterInput = new PipedInputStream(8192);
      PipedOutputStream inputFeeder = new PipedOutputStream(adapterInput);
      PipedInputStream adapterOutputReader = new PipedInputStream(8192);
      this.adapterOutput = new PipedOutputStream(adapterOutputReader);
      this.inputWriter = new BufferedWriter(new OutputStreamWriter(inputFeeder, StandardCharsets.UTF_8));
      this.outputReader = new BufferedReader(new InputStreamReader(adapterOutputReader, StandardCharsets.UTF_8));
      this.ioExecutor = Executors.newCachedThreadPool();
      this.adapterThread = new Thread(() -> adapter.start(adapterInput, adapterOutput, false), "mcp-adapter-test");
      this.adapterThread.setDaemon(true);
      this.adapterThread.start();
    }

    void sendToAdapter(String message) {
      try {
        synchronized (inputWriter) {
          inputWriter.write(message);
          inputWriter.write('\n');
          inputWriter.flush();
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    String readFromAdapter(Duration timeout) throws Exception {
      Future<String> future = ioExecutor.submit(() -> {
        try {
          return outputReader.readLine();
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
      try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        future.cancel(true);
        throw e;
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        inputWriter.close();
      } catch (IOException ignored) {
      }
      adapter.stop();
      try {
        adapterThread.join(Duration.ofSeconds(2).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      try {
        adapterOutput.close();
      } catch (IOException ignored) {
      }
      try {
        outputReader.close();
      } catch (IOException ignored) {
      }
      ioExecutor.shutdownNow();
      scheduler.shutdownNow();
    }
  }
}
