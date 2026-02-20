package com.bitsapplied.descartes.mcp.adapter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Java TCP adapter for MCP clients. Bridges stdin/stdout JSON-RPC with the MCP
 * server over TCP, mirroring the behaviour of the Node implementation while
 * adding JVM-friendly ergonomics.
 */
public final class McpTcpAdapter {
  private static final long DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS = 2_000L;
  private static final String METHOD_TOOLS_CALL = "tools/call";
  private static final String DEBUGGER_EVENTS_TOOL_NAME = "debugger_events";
  private static final String DEBUGGER_EVENTS_OPERATION_WAIT = "wait";
  private static final String DEBUGGER_EVENTS_OPERATION_WAIT_FOR = "wait_for";
  private static final String DEBUGGER_EVENTS_OPERATION_WAIT_FOR_EVENT = "wait_for_event";
  private static final long TOOL_TIMEOUT_MIN_MS = 1L;
  private static final long TOOL_TIMEOUT_MAX_MS = 600_000L;
  private static final Set<String> TOOLS_WITH_TIMEOUT_MS = Set.of("debugger_events", "debugger_step");
  private static final Set<String> TOOLS_WITH_TIMEOUT_SECONDS = Set.of("jshell_repl", "jshell_async");
  private static final Set<String> TOOLS_WITH_JDWP_TIMEOUT = Set.of("debugger_session");

  private enum ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, CLOSING
  }

  private static final class ErrorCodes {
    static final int PARSE_ERROR = -32700;
    static final int CONNECTION_ERROR = -32001;
    static final int TIMEOUT_ERROR = -32002;
    static final int QUEUE_FULL_ERROR = -32003;

    private ErrorCodes() {
    }
  }

  private static final class PendingRequest {
    final JsonNode idNode;
    final ScheduledFuture<?> timeoutFuture;
    final long effectiveTimeoutMs;

    PendingRequest(JsonNode idNode, ScheduledFuture<?> timeoutFuture, long effectiveTimeoutMs) {
      this.idNode = idNode;
      this.timeoutFuture = timeoutFuture;
      this.effectiveTimeoutMs = effectiveTimeoutMs;
    }
  }

  private static final class QueuedMessage {
    final String raw;
    final JsonNode idNode;
    final String requestKey;
    final boolean initialize;
    final long effectiveTimeoutMs;

    QueuedMessage(String raw, JsonNode idNode, String requestKey, boolean initialize, long effectiveTimeoutMs) {
      this.raw = raw;
      this.idNode = idNode;
      this.requestKey = requestKey;
      this.initialize = initialize;
      this.effectiveTimeoutMs = effectiveTimeoutMs;
    }

    boolean hasId() {
      return idNode != null;
    }
  }

  private record ToolTimeoutNormalization(boolean normalized, long toolTimeoutMs, boolean defaulted,
      boolean injectedToolArgument) {
  }

  private final AdapterConfig config;
  private final RateLimitedLogger logger;
  private final JsonRpcValidator validator;
  private final ObjectMapper objectMapper;
  private final ScheduledExecutorService scheduler;
  private final Deque<QueuedMessage> messageQueue = new ArrayDeque<>();
  private final ConcurrentHashMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
  private final Object queueLock = new Object();
  private final Object sendLock = new Object();
  private final Object reconnectLock = new Object();
  private final Object clientSendLock = new Object();
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
  private final AtomicReference<String> initializeRequestKey = new AtomicReference<>(null);
  private final AtomicInteger lastExitCode = new AtomicInteger(0);

  private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
  private volatile Socket socket;
  private volatile BufferedWriter serverWriter;
  private volatile Thread serverReaderThread;
  private volatile long reconnectDelayMs;
  private volatile int reconnectAttemptCount;
  private volatile boolean hasConnectedBefore;
  private volatile ScheduledFuture<?> reconnectFuture;
  private volatile ScheduledFuture<?> initializeTimeout;
  private volatile BufferedWriter clientWriter;
  private boolean inputClosed;
  private boolean inputClosedExitProcess;
  private int inputClosedExitCode;
  private final long toolCallDefaultTimeoutMs;

  McpTcpAdapter(AdapterConfig config, RateLimitedLogger logger, JsonRpcValidator validator, ObjectMapper objectMapper,
      ScheduledExecutorService scheduler) {
    this.config = config;
    this.logger = logger;
    this.validator = validator;
    this.objectMapper = objectMapper;
    this.scheduler = scheduler;
    this.reconnectDelayMs = config.reconnectMinDelayMs;
    this.toolCallDefaultTimeoutMs = clampToolTimeoutMs(config.requestTimeoutMs);
  }

  public static void main(String[] args) {
    AdapterConfig config = AdapterConfig.fromEnvironment();
    McpTcpAdapter adapter = create(config);
    adapter.start(System.in, System.out, true);
  }

  static McpTcpAdapter create(AdapterConfig config) {
    ScheduledExecutorService scheduler = SchedulerFactory.create();
    ObjectMapper objectMapper = new ObjectMapper();
    RateLimitedLogger logger = new RateLimitedLogger(config, scheduler);
    JsonRpcValidator validator = new JsonRpcValidator(objectMapper, config.maxMessageSizeBytes);
    return new McpTcpAdapter(config, logger, validator, objectMapper, scheduler);
  }

  void start(InputStream input, OutputStream output, boolean exitProcess) {
    if (exitProcess) {
      Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> logger.error(
          String.format(Locale.ROOT, "Uncaught exception in thread %s: %s", thread.getName(), throwable.getMessage()),
          throwable));

      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        logger.info("Received JVM shutdown hook, shutting down gracefully");
        shutdown(false, 0);
      }, "mcp-adapter-shutdown"));
    }
    this.clientWriter = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    logger.info(String.format(Locale.ROOT, "Starting MCP TCP adapter - Host: %s, Port: %d", config.host, config.port));
    logger.info(String.format(Locale.ROOT, "Configuration: Queue size: %d, Request timeout: %dms",
        config.messageQueueSize, config.requestTimeoutMs));

    connectToServer();
    readStdInLoop(reader, exitProcess);
  }

  public void stop() {
    shutdown(false, 0);
  }

  int getLastExitCode() {
    return lastExitCode.get();
  }

  private void readStdInLoop(BufferedReader reader, boolean exitProcess) {
    try (BufferedReader in = reader) {
      String line;
      while ((line = in.readLine()) != null) {
        handleInboundLine(line);
      }
      logger.info("Stdin closed, shutting down");
      markInputClosed(exitProcess, 0);
    } catch (IOException e) {
      logger.error("Error reading stdin: " + e.getMessage(), e);
      markInputClosed(exitProcess, 1);
    }
  }

  private void handleInboundLine(String line) {
    String trimmed = line == null ? "" : line.trim();
    if (trimmed.isEmpty()) {
      return;
    }

    logger.debug("Received from stdin: " + trimmed);
    JsonRpcValidator.ValidationResult validation = validator.validate(trimmed);
    if (!validation.valid()) {
      sendProtocolErrorResponse(ErrorCodes.PARSE_ERROR, validation.error());
      return;
    }

    JsonNode parsed = validation.parsed();
    ToolTimeoutNormalization timeoutNormalization = canonicalizeToolTimeout(parsed);
    String outboundRaw = trimmed;
    if (timeoutNormalization.normalized()) {
      try {
        outboundRaw = objectMapper.writeValueAsString(parsed);
      } catch (IOException e) {
        logger.error("Failed to serialize normalized request, forwarding original payload", e);
      }
      String source = timeoutNormalization.defaulted() ? "default" : "request";
      String suffix = timeoutNormalization.injectedToolArgument() ? " (tool argument injected)" : "";
      logger.debug(String.format(Locale.ROOT, "Normalized tool timeout to %dms from %s%s",
          timeoutNormalization.toolTimeoutMs(), source, suffix));
    }
    JsonNode idNode = JsonRpcValidator.extractId(parsed);
    String requestKey = keyForId(idNode);
    boolean isInitialize = JsonRpcValidator.isInitialize(parsed);
    long effectiveTimeoutMs = resolveRequestTimeoutMs(parsed);
    QueuedMessage message = new QueuedMessage(outboundRaw, idNode, requestKey, isInitialize, effectiveTimeoutMs);
    handleValidInboundMessage(message);
  }

  private void handleValidInboundMessage(QueuedMessage message) {
    if (connectionState == ConnectionState.CONNECTED && serverWriter != null) {
      if (!transmitToServer(message)) {
        if (message.initialize) {
          queueInitializeMessage(message);
        } else {
          queueMessage(message);
        }
      }
      return;
    }

    if (message.initialize) {
      logger.info("Received initialize request while disconnected - queueing and attempting to connect");
      queueInitializeMessage(message);
    } else {
      queueMessage(message);
      if (message.hasId()) {
        logger.info(String.format(Locale.ROOT, "Connection not available, request queued (queue size: %d)",
            currentQueueSize()));
      }
    }

    if (connectionState == ConnectionState.DISCONNECTED) {
      scheduleReconnect();
    }
  }

  private void queueInitializeMessage(QueuedMessage message) {
    String requestKey = message.requestKey;
    if (requestKey == null) {
      return;
    }

    initializeRequestKey.set(requestKey);
    ScheduledFuture<?> priorTimeout = initializeTimeout;
    if (priorTimeout != null) {
      priorTimeout.cancel(false);
    }
    initializeTimeout = scheduler.schedule(() -> handleInitializeTimeout(requestKey, message.idNode),
        config.requestTimeoutMs, TimeUnit.MILLISECONDS);

    synchronized (queueLock) {
      for (Iterator<QueuedMessage> it = messageQueue.iterator(); it.hasNext();) {
        QueuedMessage queued = it.next();
        if (queued.initialize) {
          it.remove();
          clearRequest(queued.idNode);
        }
      }
      messageQueue.addFirst(message);
    }
    trackRequest(message);
  }

  private void handleInitializeTimeout(String requestKey, JsonNode idNode) {
    if (!Objects.equals(initializeRequestKey.get(), requestKey)) {
      return;
    }
    initializeRequestKey.compareAndSet(requestKey, null);
    removeMessageFromQueueByKey(requestKey);
    sendErrorResponse(idNode, ErrorCodes.TIMEOUT_ERROR, "Initialize request timeout - MCP server not available");
    clearRequest(idNode);
  }

  private void queueMessage(QueuedMessage message) {
    QueuedMessage dropped = null;
    synchronized (queueLock) {
      if (messageQueue.size() >= config.messageQueueSize) {
        dropped = messageQueue.pollFirst();
      }
      messageQueue.addLast(message);
    }

    if (dropped != null) {
      if (dropped.hasId()) {
        sendErrorResponse(dropped.idNode, ErrorCodes.QUEUE_FULL_ERROR, "Message queue full - request dropped");
        clearRequest(dropped.idNode);
      }
      logger.info("Message queue full, dropped oldest message");
    }

    trackRequest(message);
    logger.debug(String.format(Locale.ROOT, "Queued message (queue size: %d)", currentQueueSize()));
  }

  private int currentQueueSize() {
    synchronized (queueLock) {
      return messageQueue.size();
    }
  }

  private void trackRequest(QueuedMessage message) {
    if (!message.hasId()) {
      return;
    }
    trackRequestInternal(message.idNode, message.requestKey, message.effectiveTimeoutMs);
  }

  private void trackRequestInternal(JsonNode idNode, String requestKey, long effectiveTimeoutMs) {
    if (requestKey == null) {
      return;
    }
    long timeoutMs = Math.max(1L, effectiveTimeoutMs);
    pendingRequests.computeIfAbsent(requestKey, key -> {
      ScheduledFuture<?> future = scheduler.schedule(() -> handleRequestTimeout(key), timeoutMs, TimeUnit.MILLISECONDS);
      return new PendingRequest(idNode.deepCopy(), future, timeoutMs);
    });
  }

  private void handleRequestTimeout(String requestKey) {
    PendingRequest pending = pendingRequests.remove(requestKey);
    if (pending == null) {
      return;
    }
    pending.timeoutFuture.cancel(false);
    sendErrorResponse(pending.idNode, ErrorCodes.TIMEOUT_ERROR,
        String.format(Locale.ROOT, "Request timeout after %dms", pending.effectiveTimeoutMs));
    logger.debug("Request " + requestKey + " timed out");
    maybeShutdownAfterInputClosed();
  }

  private void clearRequest(JsonNode idNode) {
    if (idNode == null) {
      return;
    }
    String key = keyForId(idNode);
    if (key == null) {
      return;
    }
    PendingRequest pending = pendingRequests.remove(key);
    if (pending != null) {
      pending.timeoutFuture.cancel(false);
    }
  }

  private void removeMessageFromQueueByKey(String requestKey) {
    if (requestKey == null) {
      return;
    }
    synchronized (queueLock) {
      for (Iterator<QueuedMessage> it = messageQueue.iterator(); it.hasNext();) {
        QueuedMessage queued = it.next();
        if (Objects.equals(queued.requestKey, requestKey)) {
          it.remove();
          break;
        }
      }
    }
  }

  private boolean transmitToServer(QueuedMessage message) {
    BufferedWriter writer = serverWriter;
    if (writer == null) {
      return false;
    }

    try {
      synchronized (sendLock) {
        writer.write(message.raw);
        writer.write('\n');
        writer.flush();
      }
      if (message.hasId() && !pendingRequests.containsKey(message.requestKey)) {
        trackRequest(message);
      }
      if (message.initialize) {
        clearInitializeTracking(message.requestKey);
      }
      logger.debug("Sent to server: " + message.raw);
      return true;
    } catch (IOException e) {
      logger.error("Failed to write to server: " + e.getMessage());
      sendErrorResponse(message.idNode, ErrorCodes.CONNECTION_ERROR, "Failed to send message to MCP server");
      clearInitializeTracking(message.requestKey);
      return false;
    }
  }

  private void clearInitializeTracking(String requestKey) {
    if (requestKey == null) {
      return;
    }
    if (initializeRequestKey.compareAndSet(requestKey, null)) {
      ScheduledFuture<?> timeout = initializeTimeout;
      if (timeout != null) {
        timeout.cancel(false);
      }
      initializeTimeout = null;
    }
  }

  private void processQueuedMessages() {
    List<QueuedMessage> toSend = new ArrayList<>();
    synchronized (queueLock) {
      while (!messageQueue.isEmpty()) {
        toSend.add(messageQueue.pollFirst());
      }
    }

    if (toSend.isEmpty()) {
      return;
    }

    logger.info(String.format(Locale.ROOT, "Processing %d queued messages", Integer.valueOf(toSend.size())));

    for (QueuedMessage message : toSend) {
      if (connectionState != ConnectionState.CONNECTED) {
        queueMessage(message);
        continue;
      }
      transmitToServer(message);
    }
  }

  private void connectToServer() {
    if (shuttingDown.get()) {
      return;
    }
    if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CLOSING) {
      logger.debug("Connection already in progress or closing");
      return;
    }

    connectionState = ConnectionState.CONNECTING;

    if (shouldLogAttempt()) {
      logger.info(String.format(Locale.ROOT, "Attempting to connect to %s:%d...", config.host, config.port));
    } else {
      logger.debug(String.format(Locale.ROOT, "Attempting to connect to %s:%d...", config.host, config.port));
    }

    Thread connector = new Thread(this::performConnectionAttempt, "mcp-adapter-connector");
    connector.setDaemon(true);
    connector.start();
  }

  private boolean shouldLogAttempt() {
    return reconnectAttemptCount == 0 || reconnectAttemptCount <= 3 || reconnectAttemptCount % 10 == 0;
  }

  private void performConnectionAttempt() {
    Socket newSocket = new Socket();
    try {
      newSocket.connect(new InetSocketAddress(config.host, config.port), config.requestTimeoutMs);
      configureSocket(newSocket);
      onConnected(newSocket);
    } catch (IOException e) {
      handleConnectionException(e);
      closeQuietly(newSocket);
      onConnectionAttemptFailed();
    }
  }

  private void configureSocket(Socket target) throws SocketException {
    target.setKeepAlive(true);
    target.setTcpNoDelay(true);
  }

  private void onConnected(Socket newSocket) throws IOException {
    socket = newSocket;
    serverWriter = new BufferedWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));
    connectionState = ConnectionState.CONNECTED;

    boolean isReconnection = hasConnectedBefore;
    hasConnectedBefore = true;
    reconnectDelayMs = config.reconnectMinDelayMs;
    reconnectAttemptCount = 0;
    cancelReconnectFuture();

    logger.info(String.format(Locale.ROOT, "%s to MCP server at %s:%d", isReconnection ? "Reconnected" : "Connected",
        config.host, config.port));

    startServerReader(newSocket);

    if (isReconnection) {
      sendCapabilityRefreshNotifications();
    }

    processQueuedMessages();
  }

  private void sendCapabilityRefreshNotifications() {
    try {
      sendToClient(objectMapper.writeValueAsString(validator.newNotification("notifications/tools/list_changed")));
      sendToClient(objectMapper.writeValueAsString(validator.newNotification("notifications/resources/list_changed")));
      sendToClient(objectMapper.writeValueAsString(validator.newNotification("notifications/prompts/list_changed")));
    } catch (IOException e) {
      logger.error("Failed to serialize capability change notification: " + e.getMessage(), e);
    }
  }

  private void startServerReader(Socket activeSocket) {
    Thread reader = new Thread(() -> readServerLoop(activeSocket), "mcp-adapter-server-reader");
    reader.setDaemon(true);
    serverReaderThread = reader;
    reader.start();
  }

  private void readServerLoop(Socket activeSocket) {
    StringBuilder buffer = new StringBuilder();
    try (InputStream input = activeSocket.getInputStream()) {
      byte[] data = new byte[8192];
      int read;
      while ((read = input.read(data)) != -1) {
        String chunk = new String(data, 0, read, StandardCharsets.UTF_8);
        buffer.append(chunk);
        processServerBuffer(buffer);
      }
      if (buffer.length() > 0) {
        String remaining = buffer.toString().trim();
        buffer.setLength(0);
        if (!remaining.isEmpty()) {
          handleServerMessage(remaining);
        }
      }
    } catch (IOException e) {
      if (!shuttingDown.get()) {
        logger.debug("Error reading from MCP server: " + e.getMessage());
      }
    } finally {
      onSocketClosedFromReader();
    }
  }

  private void processServerBuffer(StringBuilder buffer) {
    int newlineIndex;
    while ((newlineIndex = buffer.indexOf("\n")) >= 0) {
      String message = buffer.substring(0, newlineIndex).trim();
      buffer.delete(0, newlineIndex + 1);
      if (!message.isEmpty()) {
        handleServerMessage(message);
      }
    }
    if (buffer.length() > config.maxMessageSizeBytes) {
      logger.error("Receive buffer overflow, clearing buffer");
      buffer.setLength(0);
    }
  }

  private void handleServerMessage(String message) {
    logger.debug("Received from server: " + message);
    JsonRpcValidator.ValidationResult validation = validator.validate(message);
    if (!validation.valid()) {
      logger.error("Invalid message from server: " + validation.error());
      return;
    }
    JsonNode parsed = validation.parsed();
    JsonNode idNode = JsonRpcValidator.extractId(parsed);
    if (idNode != null) {
      clearRequest(idNode);
    }
    sendToClient(message);
    maybeShutdownAfterInputClosed();
  }

  private void onConnectionAttemptFailed() {
    if (connectionState == ConnectionState.CLOSING) {
      return;
    }
    connectionState = ConnectionState.DISCONNECTED;
    scheduleReconnect();
  }

  private void onSocketClosedFromReader() {
    closeCurrentSocket();
    if (connectionState == ConnectionState.CLOSING) {
      return;
    }

    boolean wasConnected = connectionState == ConnectionState.CONNECTED;
    connectionState = ConnectionState.DISCONNECTED;

    if (wasConnected) {
      logger.info("Connection to MCP server closed unexpectedly");
      failAllPendingRequests(ErrorCodes.CONNECTION_ERROR, "Connection to MCP server lost");
    } else {
      logger.debug("Connection attempt failed");
    }

    scheduleReconnect();
  }

  private void failAllPendingRequests(int errorCode, String message) {
    for (PendingRequest request : pendingRequests.values()) {
      request.timeoutFuture.cancel(false);
      sendErrorResponse(request.idNode, errorCode, message);
    }
    pendingRequests.clear();
    maybeShutdownAfterInputClosed();
  }

  private void scheduleReconnect() {
    if (shuttingDown.get() || connectionState == ConnectionState.CLOSING) {
      return;
    }
    synchronized (reconnectLock) {
      if (reconnectFuture != null) {
        return;
      }

      reconnectAttemptCount++;
      boolean initializeWaiting = hasInitializeWaiting();
      long baseDelay = initializeWaiting && reconnectAttemptCount <= 10 ? config.reconnectMinDelayMs : reconnectDelayMs;
      long delayWithJitter = addJitter(baseDelay);

      if (reconnectAttemptCount <= 3 || reconnectAttemptCount % 10 == 0 || initializeWaiting) {
        logger.info(String.format(Locale.ROOT, "Scheduling reconnection attempt #%d in %dms%s", reconnectAttemptCount,
            delayWithJitter, initializeWaiting ? " (initialize waiting)" : ""));
      } else {
        logger.debug(String.format(Locale.ROOT, "Scheduling reconnection attempt #%d in %dms", reconnectAttemptCount,
            delayWithJitter));
      }

      boolean backoffEligible = !initializeWaiting;
      reconnectFuture = scheduler.schedule(() -> {
        synchronized (reconnectLock) {
          reconnectFuture = null;
        }
        if (backoffEligible) {
          reconnectDelayMs = Math.min(reconnectDelayMs * 2, config.reconnectMaxDelayMs);
        }
        connectToServer();
      }, delayWithJitter, TimeUnit.MILLISECONDS);
    }
  }

  private long addJitter(long baseDelay) {
    double jitter = baseDelay * 0.2 * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
    long result = Math.round(baseDelay + jitter);
    return Math.max(0L, result);
  }

  private boolean hasInitializeWaiting() {
    if (initializeRequestKey.get() != null) {
      return true;
    }
    synchronized (queueLock) {
      for (QueuedMessage message : messageQueue) {
        if (message.initialize) {
          return true;
        }
      }
    }
    return false;
  }

  private void handleConnectionException(IOException e) {
    if (e instanceof ConnectException) {
      logger.debug("Connection refused - server may not be running");
    } else if (e instanceof SocketTimeoutException) {
      logger.debug("Connection timeout - server may be unreachable");
    } else if (e instanceof NoRouteToHostException) {
      logger.debug("Network unreachable");
    } else if (e instanceof UnknownHostException) {
      logger.debug("Host unreachable");
    } else {
      logger.debug("TCP connection error: " + e.getMessage());
    }
  }

  private void sendErrorResponse(JsonNode idNode, int code, String message) {
    sendErrorResponse(idNode, code, message, null, false);
  }

  private void sendProtocolErrorResponse(int code, String message) {
    sendErrorResponse(null, code, message, null, true);
  }

  private void sendErrorResponse(JsonNode idNode, int code, String message, JsonNode data, boolean allowNullId) {
    boolean hasId = idNode != null && !idNode.isNull();
    if (!hasId && !allowNullId) {
      logger.debug(
          String.format(Locale.ROOT, "Not sending error response without request id (code=%d): %s", code, message));
      return;
    }
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    ObjectNode error = response.putObject("error");
    error.put("code", code);
    error.put("message", message);
    if (data != null) {
      error.set("data", data);
    }
    if (hasId) {
      response.set("id", idNode);
    } else {
      response.putNull("id");
    }
    try {
      sendToClient(objectMapper.writeValueAsString(response));
      logger.debug("Sent error response: " + response.toString());
    } catch (IOException e) {
      logger.error("Failed to serialize error response: " + e.getMessage(), e);
    }
  }

  private void sendToClient(String message) {
    BufferedWriter writer = clientWriter;
    if (writer == null) {
      logger.error("Client writer not available, dropping message: " + message);
      return;
    }
    try {
      synchronized (clientSendLock) {
        writer.write(message);
        writer.write('\n');
        writer.flush();
      }
      logger.debug("Sent to client: " + message);
    } catch (IOException e) {
      logger.error("Failed to send to client: " + e.getMessage(), e);
    }
  }

  private void closeCurrentSocket() {
    Socket current = socket;
    socket = null;
    serverWriter = null;
    if (current != null) {
      closeQuietly(current);
    }
  }

  private void cancelReconnectFuture() {
    synchronized (reconnectLock) {
      if (reconnectFuture != null) {
        reconnectFuture.cancel(false);
        reconnectFuture = null;
      }
    }
  }

  private void shutdown(boolean exitProcess, int status) {
    if (!shuttingDown.compareAndSet(false, true)) {
      if (exitProcess) {
        System.exit(status);
      }
      return;
    }

    connectionState = ConnectionState.CLOSING;
    logger.info("Shutting down MCP TCP adapter");

    cancelReconnectFuture();

    ScheduledFuture<?> initTimeout = initializeTimeout;
    if (initTimeout != null) {
      initTimeout.cancel(false);
    }
    initializeTimeout = null;
    initializeRequestKey.set(null);

    closeCurrentSocket();
    Thread reader = serverReaderThread;
    if (reader != null) {
      reader.interrupt();
    }

    for (PendingRequest request : pendingRequests.values()) {
      request.timeoutFuture.cancel(false);
    }
    pendingRequests.clear();

    synchronized (queueLock) {
      messageQueue.clear();
    }

    BufferedWriter writer = clientWriter;
    clientWriter = null;
    if (writer != null) {
      synchronized (clientSendLock) {
        try {
          writer.flush();
        } catch (IOException ignored) {
        }
        try {
          writer.close();
        } catch (IOException ignored) {
        }
      }
    }

    logger.shutdown();
    scheduler.shutdownNow();
    lastExitCode.set(status);

    if (exitProcess) {
      System.exit(status);
    }
  }

  private void markInputClosed(boolean exitProcess, int exitCode) {
    synchronized (lifecycleLock) {
      if (!inputClosed) {
        inputClosed = true;
        inputClosedExitProcess = exitProcess;
        inputClosedExitCode = exitCode;
      } else {
        if (exitProcess) {
          inputClosedExitProcess = true;
        }
        if (exitCode > inputClosedExitCode) {
          inputClosedExitCode = exitCode;
        }
      }
    }
    maybeShutdownAfterInputClosed();
  }

  private void maybeShutdownAfterInputClosed() {
    if (!inputClosed || shuttingDown.get()) {
      return;
    }
    if (!pendingRequests.isEmpty()) {
      return;
    }
    synchronized (queueLock) {
      if (!messageQueue.isEmpty()) {
        return;
      }
    }
    boolean exitProcess;
    int exitCode;
    synchronized (lifecycleLock) {
      exitProcess = inputClosedExitProcess;
      exitCode = inputClosedExitCode;
    }
    shutdown(exitProcess, exitCode);
  }

  private static String keyForId(JsonNode idNode) {
    if (idNode == null || idNode.isNull()) {
      return null;
    }
    return idNode.toString();
  }

  private long resolveRequestTimeoutMs(JsonNode parsed) {
    long baseTimeout = config.requestTimeoutMs;
    if (parsed == null || !parsed.isObject()) {
      return baseTimeout;
    }

    String method = textValue(parsed.get("method"));
    if (!METHOD_TOOLS_CALL.equals(method)) {
      return baseTimeout;
    }

    JsonNode params = parsed.get("params");
    if (params == null || !params.isObject()) {
      return baseTimeout;
    }

    Long toolTimeoutMs = resolveToolTimeoutMsFromRequest(parsed);
    if (toolTimeoutMs == null) {
      return baseTimeout;
    }

    String toolName = textValue(params.get("name"));
    String normalizedToolName = normalizeToolName(toolName);
    if (!DEBUGGER_EVENTS_TOOL_NAME.equals(normalizedToolName)) {
      return toolTimeoutMs;
    }

    JsonNode arguments = params.get("arguments");
    if (arguments == null || !arguments.isObject()) {
      return toolTimeoutMs;
    }

    String operation = normalizeDebuggerEventsOperation(textValue(arguments.get("operation")));
    if (!DEBUGGER_EVENTS_OPERATION_WAIT.equals(operation)) {
      return toolTimeoutMs;
    }

    long paddedTimeout = toolTimeoutMs > Long.MAX_VALUE - DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS ? Long.MAX_VALUE
        : toolTimeoutMs + DEBUGGER_EVENTS_WAIT_TIMEOUT_GRACE_MS;
    long effectiveTimeout = paddedTimeout;
    logger.debug(String.format(Locale.ROOT,
        "Extending timeout for %s call (name=%s, normalized_name=%s, operation=%s, wait_timeout_ms=%d, effective_timeout_ms=%d)",
        METHOD_TOOLS_CALL, toolName, normalizedToolName, operation, toolTimeoutMs, effectiveTimeout));
    return effectiveTimeout;
  }

  private ToolTimeoutNormalization canonicalizeToolTimeout(JsonNode parsed) {
    if (parsed == null || !parsed.isObject()) {
      return new ToolTimeoutNormalization(false, 0L, false, false);
    }
    if (!METHOD_TOOLS_CALL.equals(textValue(parsed.get("method")))) {
      return new ToolTimeoutNormalization(false, 0L, false, false);
    }

    ObjectNode params = asObjectNode(parsed.get("params"));
    if (params == null) {
      return new ToolTimeoutNormalization(false, 0L, false, false);
    }

    Long explicit = resolveExplicitToolTimeoutMs(parsed);
    Long resolved = resolveToolTimeoutMsFromRequest(parsed);
    if (resolved == null) {
      return new ToolTimeoutNormalization(false, 0L, false, false);
    }

    params.put("timeout_ms", resolved);
    boolean injectedToolArgument = maybeInjectToolTimeoutArgument(params, resolved);
    return new ToolTimeoutNormalization(true, resolved, explicit == null, injectedToolArgument);
  }

  private boolean maybeInjectToolTimeoutArgument(ObjectNode params, long timeoutMs) {
    String toolName = normalizeToolName(textValue(params.get("name")));
    if (toolName == null) {
      return false;
    }

    boolean supportsTimeoutMs = TOOLS_WITH_TIMEOUT_MS.contains(toolName);
    boolean supportsTimeoutSeconds = TOOLS_WITH_TIMEOUT_SECONDS.contains(toolName);
    boolean supportsJdwpTimeout = TOOLS_WITH_JDWP_TIMEOUT.contains(toolName);
    if (!supportsTimeoutMs && !supportsTimeoutSeconds && !supportsJdwpTimeout) {
      return false;
    }

    ObjectNode arguments = asObjectNode(params.get("arguments"));
    if (arguments == null) {
      arguments = objectMapper.createObjectNode();
      params.set("arguments", arguments);
    }

    if (supportsTimeoutMs) {
      Long current = longValue(arguments.get("timeout_ms"));
      if (current != null && current == timeoutMs) {
        return false;
      }
      arguments.put("timeout_ms", timeoutMs);
      return true;
    }

    if (supportsTimeoutSeconds) {
      long seconds = Math.max(1L, (timeoutMs + 999L) / 1000L);
      Long currentSeconds = longValue(arguments.get("timeout_seconds"));
      if (currentSeconds != null && currentSeconds == seconds) {
        return false;
      }
      arguments.put("timeout_seconds", seconds);
      return true;
    }

    if (supportsJdwpTimeout) {
      Long current = longValue(arguments.get("jdwp_timeout"));
      if (current != null && current == timeoutMs) {
        return false;
      }
      arguments.put("jdwp_timeout", timeoutMs);
      return true;
    }

    return false;
  }

  private Long resolveToolTimeoutMsFromRequest(JsonNode parsed) {
    if (parsed == null || !parsed.isObject()) {
      return null;
    }
    if (!METHOD_TOOLS_CALL.equals(textValue(parsed.get("method")))) {
      return null;
    }
    JsonNode params = parsed.get("params");
    if (params == null || !params.isObject()) {
      return null;
    }

    Long explicit = resolveExplicitToolTimeoutMs(parsed);
    long requested = explicit != null ? explicit : toolCallDefaultTimeoutMs;
    return clampToolTimeoutMs(requested);
  }

  private Long resolveExplicitToolTimeoutMs(JsonNode parsed) {
    if (parsed == null || !parsed.isObject()) {
      return null;
    }
    if (!METHOD_TOOLS_CALL.equals(textValue(parsed.get("method")))) {
      return null;
    }

    JsonNode params = parsed.get("params");
    if (params == null || !params.isObject()) {
      return null;
    }

    JsonNode arguments = params.get("arguments");
    Long argumentTimeoutMs = longValue(arguments != null ? arguments.get("timeout_ms") : null);
    if (argumentTimeoutMs == null) {
      argumentTimeoutMs = longValue(arguments != null ? arguments.get("timeoutMs") : null);
    }
    if (argumentTimeoutMs != null && argumentTimeoutMs >= 0) {
      return argumentTimeoutMs;
    }

    Long paramsTimeoutMs = longValue(params.get("timeout_ms"));
    if (paramsTimeoutMs == null) {
      paramsTimeoutMs = longValue(params.get("timeoutMs"));
    }
    if (paramsTimeoutMs != null && paramsTimeoutMs >= 0) {
      return paramsTimeoutMs;
    }

    Long timeoutSeconds = longValue(arguments != null ? arguments.get("timeout_seconds") : null);
    if (timeoutSeconds == null) {
      timeoutSeconds = longValue(arguments != null ? arguments.get("timeoutSeconds") : null);
    }
    if (timeoutSeconds != null && timeoutSeconds > 0) {
      return millisecondsFromSeconds(timeoutSeconds);
    }

    Long jdwpTimeoutMs = longValue(arguments != null ? arguments.get("jdwp_timeout") : null);
    if (jdwpTimeoutMs == null) {
      jdwpTimeoutMs = longValue(arguments != null ? arguments.get("jdwpTimeout") : null);
    }
    if (jdwpTimeoutMs != null && jdwpTimeoutMs > 0) {
      return jdwpTimeoutMs;
    }

    return null;
  }

  private static long clampToolTimeoutMs(long timeoutMs) {
    return Math.min(Math.max(timeoutMs, TOOL_TIMEOUT_MIN_MS), TOOL_TIMEOUT_MAX_MS);
  }

  private static long millisecondsFromSeconds(long timeoutSeconds) {
    if (timeoutSeconds >= Long.MAX_VALUE / 1000L) {
      return Long.MAX_VALUE;
    }
    return timeoutSeconds * 1000L;
  }

  private static ObjectNode asObjectNode(JsonNode node) {
    return node instanceof ObjectNode objectNode ? objectNode : null;
  }

  private static String normalizeToolName(String rawName) {
    if (rawName == null) {
      return null;
    }
    String normalized = rawName.trim();
    int separator = Math.max(normalized.lastIndexOf('.'), normalized.lastIndexOf('/'));
    return separator >= 0 && separator + 1 < normalized.length() ? normalized.substring(separator + 1) : normalized;
  }

  private static String normalizeDebuggerEventsOperation(String operation) {
    if (operation == null) {
      return null;
    }
    String normalized = operation.trim();
    return switch (normalized) {
    case DEBUGGER_EVENTS_OPERATION_WAIT_FOR, DEBUGGER_EVENTS_OPERATION_WAIT_FOR_EVENT -> DEBUGGER_EVENTS_OPERATION_WAIT;
    default -> normalized;
    };
  }

  private static String textValue(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private static Long longValue(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isIntegralNumber()) {
      return node.longValue();
    }
    if (node.isTextual()) {
      try {
        return Long.parseLong(node.asText().trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // ignore
    }
  }

  private static final class SchedulerFactory {
    private SchedulerFactory() {
    }

    static ScheduledExecutorService create() {
      ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2,
          new AdapterThreadFactory("mcp-adapter-scheduler"));
      executor.setRemoveOnCancelPolicy(true);
      executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
      return executor;
    }
  }

  private static final class AdapterThreadFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();
    private final String prefix;

    AdapterThreadFactory(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable);
      thread.setName(prefix + "-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
