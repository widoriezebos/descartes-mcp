package com.bitsapplied.descartes;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.bitsapplied.descartes.debugger.integration.DebuggerNotificationBroadcaster;
import com.bitsapplied.descartes.debugger.integration.MCPEventBridge.DebuggerNotification;
import com.bitsapplied.descartes.mcp.MCPNotificationDispatcher;
import com.bitsapplied.descartes.resources.MCPResource;
import com.bitsapplied.descartes.resources.MCPResource.ResourceNotFoundException;
import com.bitsapplied.descartes.resources.MCPResource.ResourceReadResult;
import com.bitsapplied.descartes.settings.SettingsProvider;
import com.bitsapplied.descartes.tools.MCPTool;
import com.bitsapplied.descartes.tools.ToolExecutionException;
import com.bitsapplied.descartes.tools.ToolResponse;
import com.bitsapplied.descartes.util.JShellSessionManagers;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Generic MCP (Model Context Protocol) server implementation.
 *
 * <p>
 * This server can be used standalone or integrated into any application to
 * expose tools and resources via the MCP protocol. The server implements
 * JSON-RPC 2.0 over TCP sockets for communication with MCP clients.
 *
 * <h2>Architecture</h2>
 * <ul>
 * <li><b>Protocol</b>: JSON-RPC 2.0 with MCP-specific methods</li>
 * <li><b>Transport</b>: TCP sockets (default port: 9080)</li>
 * <li><b>Threading</b>: Cached thread pool for handling concurrent client
 * connections</li>
 * <li><b>Tools</b>: Callable functions exposed to clients (e.g., debugging,
 * profiling)</li>
 * <li><b>Resources</b>: Read-only data providers (e.g., metrics, thread
 * dumps)</li>
 * <li><b>Context</b>: Generic Map for sharing application objects across tools
 * and resources</li>
 * </ul>
 *
 * <h2>Threading Model</h2>
 * <ul>
 * <li>Main thread accepts client connections on server socket</li>
 * <li>Each client connection handled by dedicated thread from executor
 * pool</li>
 * <li>Tool execution asynchronous with configurable timeout (default: 60s, max:
 * 10 min)</li>
 * <li>Proper executor shutdown with 10-second await termination period</li>
 * </ul>
 *
 * <h2>Key Features</h2>
 * <ul>
 * <li>Multiple concurrent client connections supported</li>
 * <li>Tool execution with timeout protection</li>
 * <li>Notification support for server-to-client events (e.g., debugger
 * events)</li>
 * <li>Generic context for application integration without tight coupling</li>
 * <li>Graceful shutdown with resource cleanup</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>
 * SettingsProvider settings = SettingsProvider.defaults();
 * Map&lt;String, Object&gt; context = new HashMap&lt;&gt;();
 * context.put("appService", myService);
 *
 * MCPServer server = new MCPServer(settings, 9080, context);
 * server.registerTool(new JShellTool(context));
 * server.registerResource(new MetricsResource());
 * server.start(); // Blocks until stopped
 * </pre>
 *
 * @see MCPTool
 * @see MCPResource
 * @see SettingsProvider
 */
public class MCPServer {
  private static final Logger logger = LogManager.getLogger(MCPServer.class);

  // Protocol constants
  private static final String JSONRPC_VERSION = "2.0";
  private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
  private String serverName = "Descartes MCP Server";
  private String serverVersion = "1.0.0";

  // JSON-RPC error codes
  private static final int ERROR_METHOD_NOT_FOUND = -32601;
  private static final int ERROR_INVALID_PARAMS = -32602;
  private static final int ERROR_INTERNAL = -32603;
  private static final int ERROR_INVALID_REQUEST = -32600;

  // Security limits
  private static final int MAX_MESSAGE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB

  // MCP method names
  private static final String METHOD_INITIALIZE = "initialize";
  private static final String METHOD_TOOLS_LIST = "tools/list";
  private static final String METHOD_TOOLS_CALL = "tools/call";
  private static final String METHOD_RESOURCES_LIST = "resources/list";
  private static final String METHOD_RESOURCES_READ = "resources/read";
  private static final String METHOD_PING = "ping";

  protected final List<MCPTool> tools;
  protected final List<MCPResource> resources;
  private final ObjectMapper objectMapper;
  private final int port;

  // Generic context for tools and resources
  private final Map<String, Object> context;
  private final SettingsProvider settings;

  private ServerSocket serverSocket;
  private ExecutorService executorService;
  private volatile boolean running = false;
  private final long toolExecutionTimeoutMs;
  private final Set<MCPNotificationDispatcher> activeDispatchers = ConcurrentHashMap.newKeySet();
  private final AutoCloseable debuggerNotificationRegistration;

  /**
   * Creates a new MCP server with settings and port.
   * 
   * @param settings the settings provider
   * @param port     the port to listen on
   */
  public MCPServer(SettingsProvider settings, int port) {
    this(settings, port, new ConcurrentHashMap<>());
  }

  /**
   * Creates a new MCP server with settings, port, and context.
   * 
   * @param settings the settings provider
   * @param port     the port to listen on
   * @param context  application-specific context objects
   */
  public MCPServer(SettingsProvider settings, int port, Map<String, Object> context) {
    this.settings = settings;
    this.port = port;
    this.context = context;
    this.tools = new ArrayList<>();
    this.resources = new ArrayList<>();
    this.objectMapper = new ObjectMapper();
    this.executorService = Executors.newCachedThreadPool();
    this.toolExecutionTimeoutMs = Math.max(1000L, settings.getInt("mcp.tools.timeout.ms", 60000));
    this.debuggerNotificationRegistration = DebuggerNotificationBroadcaster.getInstance()
        .registerListener(this::handleDebuggerNotification);
  }

  /**
   * Gets the context map for accessing application-specific objects. Tools and
   * resources can use this to access shared state.
   * 
   * @return the context map
   */
  public Map<String, Object> getContext() {
    return context;
  }

  /**
   * Gets the settings provider.
   * 
   * @return the settings provider
   */
  public SettingsProvider getSettings() {
    return settings;
  }

  /**
   * Sets the server name for identification.
   */
  public void setServerName(String name) {
    this.serverName = name;
  }

  /**
   * Sets the server version.
   */
  public void setServerVersion(String version) {
    this.serverVersion = version;
  }

  /**
   * Registers a tool with the MCP server.
   * 
   * @param tool the tool to register
   */
  public void registerTool(MCPTool tool) {
    tools.add(tool);
    logger.info("Registered MCP tool: {}", tool.getToolName());
  }

  /**
   * Registers a resource provider with the MCP server.
   * 
   * @param resource the resource provider to register
   */
  public void registerResource(MCPResource resource) {
    resources.add(resource);
    logger.info("Registered MCP resource provider");
  }

  /**
   * Starts the MCP server and begins accepting client connections.
   * 
   * @throws Exception if the server fails to start
   */
  public void start() throws Exception {
    logger.info("Starting MCP server on port {}", port);

    serverSocket = new ServerSocket(port);
    running = true;

    ensureExecutor().submit(this::acceptConnections);

    logger.info("MCP server started successfully on port {}", port);
  }

  /**
   * Continuously accepts client connections while the server is running.
   */
  private void acceptConnections() {
    while (running) {
      Socket clientSocket = null;
      try {
        clientSocket = serverSocket.accept();
        logger.info("New MCP client connected from {}", clientSocket.getRemoteSocketAddress());

        // Create final reference for lambda
        final Socket finalSocket = clientSocket;
        ensureExecutor().submit(() -> handleClient(finalSocket));
      } catch (RejectedExecutionException e) {
        logger.error("Cannot accept client connection - executor rejected task (may be shutting down)", e);
        // Attempt to close the client socket gracefully
        try {
          if (clientSocket != null && !clientSocket.isClosed()) {
            clientSocket.close();
          }
        } catch (IOException ioEx) {
          logger.debug("Error closing rejected client socket", ioEx);
        }
      } catch (SocketException e) {
        if (running) {
          logger.error("Socket error accepting client connection", e);
        }
      } catch (IOException e) {
        if (running) {
          logger.error("IO error accepting client connection", e);
        }
      } catch (Exception e) {
        if (running) {
          logger.error("Unexpected error accepting client connection", e);
        }
      }
    }
  }

  /**
   * Handles communication with a connected client.
   *
   * @param clientSocket the client socket connection
   */
  private void handleClient(Socket clientSocket) {
    ClientConnectionContext connectionContext = null;

    try {
      // Configure socket timeouts to prevent resource exhaustion attacks
      clientSocket.setSoTimeout(300000); // 5 minute read timeout
      clientSocket.setKeepAlive(true);
      clientSocket.setTcpNoDelay(true); // Disable Nagle's algorithm for lower latency
    } catch (IOException e) {
      logger.error("Failed to configure client socket", e);
      try {
        clientSocket.close();
      } catch (IOException closeEx) {
        logger.error("Error closing socket after configuration failure", closeEx);
      }
      return;
    }

    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {

      OutputStream rawOutput = clientSocket.getOutputStream();
      Object writeLock = new Object();
      BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(rawOutput, StandardCharsets.UTF_8));
      MCPNotificationDispatcher dispatcher = new MCPNotificationDispatcher(rawOutput, writeLock);

      connectionContext = new ClientConnectionContext(clientSocket, writer, dispatcher, writeLock);
      activeDispatchers.add(dispatcher);

      logger.debug("Notification dispatcher created for client {}", clientSocket.getRemoteSocketAddress());

      processClientRequests(reader, connectionContext);

    } catch (SocketException e) {
      logger.debug("Client disconnected: {}", e.getMessage());
    } catch (EOFException e) {
      logger.debug("Client closed connection gracefully");
    } catch (Exception e) {
      logger.error("Error handling client", e);
    } finally {
      if (connectionContext != null) {
        activeDispatchers.remove(connectionContext.dispatcher());
        try {
          connectionContext.close();
        } catch (Exception e) {
          logger.error("Error closing client connection", e);
        }
      } else {
        try {
          clientSocket.close();
        } catch (IOException e) {
          logger.error("Error closing client socket", e);
        }
      }
      logger.info("Client disconnected");
    }
  }

  /**
   * Reads a line from the reader with a size limit to prevent DoS attacks.
   *
   * @param reader the BufferedReader to read from
   * @return the line read, or null if EOF
   * @throws IOException if an I/O error occurs or message exceeds size limit
   */
  private String readLineWithLimit(BufferedReader reader) throws IOException {
    StringBuilder sb = new StringBuilder();
    int bytesRead = 0;
    int c;

    while ((c = reader.read()) != -1) {
      if (c == '\n') {
        break;
      }
      if (c != '\r') { // Skip carriage returns
        sb.append((char) c);
        // Approximate byte size (conservative - assumes each char is up to 4 bytes in
        // UTF-8)
        bytesRead += 4;
        if (bytesRead > MAX_MESSAGE_SIZE_BYTES) {
          throw new IOException("Message size exceeds limit: " + MAX_MESSAGE_SIZE_BYTES + " bytes");
        }
      }
    }

    return (sb.length() == 0 && c == -1) ? null : sb.toString();
  }

  /**
   * Processes incoming requests from the client.
   */
  @SuppressWarnings("unchecked")
  private void processClientRequests(BufferedReader reader, ClientConnectionContext connectionContext)
      throws Exception {
    String line;
    while ((line = readLineWithLimit(reader)) != null) {
      boolean notification = false;
      try {
        Map<String, Object> request = objectMapper.readValue(line, Map.class);
        notification = !request.containsKey("id");
        Map<String, Object> response = handleRequest(request);
        if (!notification && response != null) {
          connectionContext.sendResponse(objectMapper.writeValueAsString(response));
        }
      } catch (IOException e) {
        // IOException from readLineWithLimit indicates message size exceeded or I/O
        // error
        logger.error("I/O error reading request", e);
        if (!notification) {
          // Use ERROR_INVALID_REQUEST for message size violations (malformed/oversized
          // request)
          Map<String, Object> errorResponse = buildErrorResponse(null, ERROR_INVALID_REQUEST,
              "Invalid request: " + e.getMessage());
          connectionContext.sendResponse(objectMapper.writeValueAsString(errorResponse));
        }
      } catch (Exception e) {
        logger.error("Error handling request", e);
        if (!notification) {
          Map<String, Object> errorResponse = buildErrorResponse(null, ERROR_INTERNAL,
              "Internal error: " + e.getMessage());
          connectionContext.sendResponse(objectMapper.writeValueAsString(errorResponse));
        }
      }
    }
  }

  /**
   * Lightweight per-client context that keeps the dispatcher and synchronized
   * writer together.
   */
  private static final class ClientConnectionContext implements AutoCloseable {
    private final Socket socket;
    private final BufferedWriter writer;
    private final MCPNotificationDispatcher dispatcher;
    private final Object writeLock;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ClientConnectionContext(Socket socket, BufferedWriter writer, MCPNotificationDispatcher dispatcher,
        Object writeLock) {
      this.socket = socket;
      this.writer = writer;
      this.dispatcher = dispatcher;
      this.writeLock = writeLock;
    }

    MCPNotificationDispatcher dispatcher() {
      return dispatcher;
    }

    void sendResponse(String json) throws IOException {
      synchronized (writeLock) {
        writer.write(json);
        writer.write('\n');
        writer.flush();
      }
    }

    @Override
    public void close() throws IOException {
      if (!closed.compareAndSet(false, true)) {
        return;
      }

      IOException primaryFailure = null;
      List<IOException> suppressedFailures = new ArrayList<>();

      // Try to close dispatcher
      try {
        dispatcher.close();
      } catch (IOException e) {
        primaryFailure = e;
      }

      // Try to flush writer
      try {
        synchronized (writeLock) {
          writer.flush();
        }
      } catch (IOException e) {
        if (primaryFailure == null) {
          primaryFailure = e;
        } else {
          suppressedFailures.add(e);
        }
      }

      // Try to close writer
      try {
        writer.close();
      } catch (IOException e) {
        if (primaryFailure == null) {
          primaryFailure = e;
        } else {
          suppressedFailures.add(e);
        }
      }

      // Try to close socket
      try {
        socket.close();
      } catch (IOException e) {
        if (primaryFailure == null) {
          primaryFailure = e;
        } else {
          suppressedFailures.add(e);
        }
      }

      // Attach all suppressed exceptions to primary failure
      if (primaryFailure != null) {
        suppressedFailures.forEach(primaryFailure::addSuppressed);
        throw primaryFailure;
      }
    }
  }

  /**
   * Routes and handles incoming JSON-RPC requests.
   * 
   * @param request the JSON-RPC request
   * @return the JSON-RPC response
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> handleRequest(Map<String, Object> request) {
    String method = (String) request.get("method");
    Object id = request.get("id");
    Map<String, Object> params = (Map<String, Object>) request.get("params");

    try {
      Object result = routeMethod(method, params);
      return buildSuccessResponse(id, result);
    } catch (MethodNotFoundException e) {
      return buildErrorResponse(id, ERROR_METHOD_NOT_FOUND, e.getMessage());
    } catch (ToolExecutionException e) {
      // Tool error with structured data - preserve error code and details
      return buildErrorResponse(id, e.getErrorCode(), e.getMessage(), e.getErrorData());
    } catch (InvalidParametersException e) {
      return buildErrorResponse(id, ERROR_INVALID_PARAMS, e.getMessage());
    } catch (Exception e) {
      logger.error("Internal error processing method: " + method, e);
      return buildErrorResponse(id, ERROR_INTERNAL, "Internal error: " + e.getMessage());
    }
  }

  /**
   * Routes the method call to the appropriate handler.
   */
  private Object routeMethod(String method, Map<String, Object> params)
      throws MethodNotFoundException, InvalidParametersException {

    // Validate method parameter
    if (method == null) {
      throw new MethodNotFoundException("Method name is required");
    }

    switch (method) {
    case METHOD_INITIALIZE:
      return handleInitialize();

    case METHOD_TOOLS_LIST:
      return handleToolsList();

    case METHOD_TOOLS_CALL:
      return handleToolsCall(params);

    case METHOD_RESOURCES_LIST:
      return handleResourcesList();

    case METHOD_RESOURCES_READ:
      return handleResourcesRead(params);

    case METHOD_PING:
      return handlePing();

    default:
      throw new MethodNotFoundException("Method not found: " + method);
    }
  }

  /**
   * Handles the initialize method.
   */
  private Map<String, Object> handleInitialize() {
    return Map.of("protocolVersion", MCP_PROTOCOL_VERSION, "capabilities",
        Map.of("tools", Map.of("listChanged", true), "resources", Map.of("listChanged", true, "subscribe", false)),
        "serverInfo", Map.of("name", serverName, "version", serverVersion));
  }

  /**
   * Handles the ping method for health checks.
   */
  private String handlePing() {
    return "pong";
  }

  /**
   * Handles the tools/list method.
   */
  private Map<String, Object> handleToolsList() {
    List<Map<String, Object>> toolList = tools.stream().map(tool -> Map.of("name", tool.getToolName(), "description",
        tool.getToolDescription(), "inputSchema", tool.getToolSchema())).toList();

    return Map.of("tools", toolList);
  }

  /**
   * Handles the tools/call method with async execution.
   *
   * <p>
   * <b>Phase 1a Update:</b> Now supports async tool execution using
   * {@link CompletableFuture} and {@link ToolResponse} for structured error
   * handling.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> handleToolsCall(Map<String, Object> params) throws InvalidParametersException {

    String toolName = (String) params.get("name");
    Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

    MCPTool tool = findToolByName(toolName);
    if (tool == null) {
      throw new InvalidParametersException("Unknown tool: " + toolName);
    }

    long timeoutMs = resolveToolTimeout(params, arguments);

    try {

      // Execute tool asynchronously and wait for result
      CompletableFuture<ToolResponse> future = tool.executeAsync(arguments);
      ToolResponse response = future.get(timeoutMs, TimeUnit.MILLISECONDS);

      // Handle success or error response
      return switch (response) {
      case ToolResponse.Success success -> {
        // Check if response is JSON format (structured data) or text format
        String format = (String) success.metadata().get(ToolResponse.METADATA_FORMAT);
        boolean isJsonFormat = ToolResponse.FORMAT_JSON.equals(format);

        Map<String, Object> contentItem;
        if (isJsonFormat) {
          // JSON format: parse and embed directly to avoid double-encoding
          try {
            contentItem = Map.of("type", "text", "text", success.content());
            // Note: We keep "text" type for MCP compatibility, but content is JSON
          } catch (Exception e) {
            logger.warn("Failed to parse JSON format response, falling back to text: {}", e.getMessage());
            contentItem = Map.of("type", "text", "text", success.content());
          }
        } else {
          // Text format (default): wrap in MCP text content
          contentItem = Map.of("type", "text", "text", success.content());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("content", List.of(contentItem));

        // Add metadata if present (excluding internal format indicator)
        if (!success.metadata().isEmpty()) {
          Map<String, Object> userMetadata = new HashMap<>(success.metadata());
          userMetadata.remove(ToolResponse.METADATA_FORMAT); // Remove internal metadata
          if (!userMetadata.isEmpty()) {
            result.put("_meta", userMetadata);
          }
        }

        yield result;
      }

      case ToolResponse.Error error -> {
        // Tool returned an error - preserve structured error data for JSON-RPC response
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("tool_name", toolName);
        errorData.put("tool_error_code", error.code());
        if (!error.details().isEmpty()) {
          errorData.put("details", error.details());
        }

        // Map tool error codes to JSON-RPC error codes
        int jsonRpcCode = mapToolErrorToJsonRpc(error.code());

        throw new ToolExecutionException(jsonRpcCode,
            String.format("Tool '%s' error [%d]: %s", toolName, error.code(), error.message()), errorData);
      }
      };

    } catch (ToolExecutionException e) {
      // Re-throw ToolExecutionException with structured error data
      throw e;
    } catch (TimeoutException e) {
      logger.error("Tool execution timeout: {} ({} ms)", toolName, timeoutMs);
      throw new InvalidParametersException("Tool execution timeout: " + toolName);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.error("Tool execution interrupted: {}", toolName);
      throw new InvalidParametersException("Tool execution interrupted: " + toolName);
    } catch (ExecutionException e) {
      logger.error("Tool execution failed: " + toolName, e.getCause());
      throw new InvalidParametersException("Tool execution failed: " + e.getCause().getMessage());
    } catch (Exception e) {
      logger.error("Unexpected error executing tool: " + toolName, e);
      throw new InvalidParametersException("Tool execution failed: " + e.getMessage());
    }
  }

  /**
   * Finds a tool by name.
   * 
   * @param toolName the name of the tool to find
   * @return the tool, or null if not found
   */
  private MCPTool findToolByName(String toolName) {
    return tools.stream().filter(tool -> tool.getToolName().equals(toolName)).findFirst().orElse(null);
  }

  /**
   * Handles the resources/list method.
   */
  private Map<String, Object> handleResourcesList() {
    List<Map<String, Object>> allResources = new ArrayList<>();
    for (MCPResource resource : resources) {
      allResources.addAll(resource.listResources());
    }
    return Map.of("resources", allResources);
  }

  /**
   * Handles the resources/read method.
   */
  private Map<String, Object> handleResourcesRead(Map<String, Object> params) throws InvalidParametersException {

    String uri = (String) params.get("uri");
    if (uri == null) {
      throw new InvalidParametersException("Missing required parameter: uri");
    }

    // Try each resource provider until one can handle the URI
    for (MCPResource resource : resources) {
      try {
        ResourceReadResult resourceResult = resource.readResourceDetailed(uri);
        Map<String, Object> content = new HashMap<>();
        content.put("uri", uri);
        content.put("mimeType", resourceResult.mimeType());
        content.put("text", resourceResult.content());
        return Map.of("contents", List.of(content));
      } catch (ResourceNotFoundException e) {
        continue;
      } catch (MCPResource.ResourceException e) {
        String message = e.getMessage() != null ? e.getMessage() : "Resource read failed";
        throw new InvalidParametersException(message);
      }
    }

    throw new InvalidParametersException("Resource not found: " + uri);
  }

  private long resolveToolTimeout(Map<String, Object> params, Map<String, Object> arguments) {
    Object timeoutValue = null;
    if (params != null && params.containsKey("timeoutMs")) {
      timeoutValue = params.get("timeoutMs");
    }
    if (timeoutValue == null && arguments != null && arguments.containsKey("timeoutMs")) {
      timeoutValue = arguments.get("timeoutMs");
    }

    if (timeoutValue instanceof Number number) {
      // Apply bounds: minimum 1 second, maximum 10 minutes (600,000ms)
      return Math.min(Math.max(1000L, number.longValue()), 600000L);
    }
    if (timeoutValue instanceof String str) {
      try {
        // Apply same bounds for string values
        return Math.min(Math.max(1000L, Long.parseLong(str)), 600000L);
      } catch (NumberFormatException ignored) {
        logger.warn("Invalid timeoutMs value '{}', falling back to default {}", str, toolExecutionTimeoutMs);
      }
    }
    return toolExecutionTimeoutMs;
  }

  /**
   * Builds a successful JSON-RPC response.
   */
  private Map<String, Object> buildSuccessResponse(Object id, Object result) {
    Map<String, Object> response = new HashMap<>();
    response.put("jsonrpc", JSONRPC_VERSION);
    if (id != null) {
      response.put("id", id);
    }
    response.put("result", result);
    return response;
  }

  /**
   * Builds an error JSON-RPC response.
   */
  private Map<String, Object> buildErrorResponse(Object id, int code, String message) {
    return buildErrorResponse(id, code, message, null);
  }

  /**
   * Builds an error JSON-RPC response with additional error data.
   *
   * <p>
   * The data field is an optional member that contains additional information
   * about the error. This allows preserving structured error details from tools.
   *
   * @param id      the request ID (may be null)
   * @param code    the JSON-RPC error code
   * @param message the error message
   * @param data    optional additional error data (original tool error code,
   *                details, etc.)
   * @return the error response map
   */
  private Map<String, Object> buildErrorResponse(Object id, int code, String message, Map<String, Object> data) {
    Map<String, Object> response = new HashMap<>();
    response.put("jsonrpc", JSONRPC_VERSION);
    if (id != null) {
      response.put("id", id);
    }

    Map<String, Object> error = new HashMap<>();
    error.put("code", code);
    error.put("message", message);
    if (data != null && !data.isEmpty()) {
      error.put("data", data);
    }

    response.put("error", error);
    return response;
  }

  /**
   * Maps tool error codes to JSON-RPC error code ranges.
   *
   * <p>
   * Mapping strategy:
   * <ul>
   * <li>1000-1999: Parameter/validation errors → -32602 (Invalid params)</li>
   * <li>2000-2999: Execution errors → -32603 (Internal error)</li>
   * <li>3000+: Domain-specific errors → -32000 (Server error)</li>
   * <li>Other: -32603 (Internal error as fallback)</li>
   * </ul>
   *
   * @param toolErrorCode the tool's error code
   * @return the corresponding JSON-RPC error code
   */
  private int mapToolErrorToJsonRpc(int toolErrorCode) {
    if (toolErrorCode >= 1000 && toolErrorCode < 2000) {
      return ERROR_INVALID_PARAMS; // -32602
    } else if (toolErrorCode >= 2000 && toolErrorCode < 3000) {
      return ERROR_INTERNAL; // -32603
    } else if (toolErrorCode >= 3000) {
      return -32000; // Server error (implementation-defined)
    } else {
      return ERROR_INTERNAL; // -32603 (fallback)
    }
  }

  private void handleDebuggerNotification(DebuggerNotification notification) {
    Map<String, Object> payload = notification.toMCPNotification();
    String method = (String) payload.get("method");
    @SuppressWarnings("unchecked")
    Map<String, Object> params = (Map<String, Object>) payload.get("params");

    if (method == null || params == null) {
      logger.warn("Ignoring debugger notification with missing method or params: {}", payload);
      return;
    }

    for (MCPNotificationDispatcher dispatcher : activeDispatchers) {
      try {
        dispatcher.sendNotification(method, params);
      } catch (Exception e) {
        logger.error("Failed to deliver debugger notification to client", e);
      }
    }
  }

  /**
   * Gracefully stops the MCP server.
   */
  public void stop() {
    logger.info("Stopping MCP server");
    running = false;

    for (MCPNotificationDispatcher dispatcher : activeDispatchers) {
      try {
        dispatcher.close();
      } catch (IOException e) {
        logger.warn("Error closing dispatcher during shutdown", e);
      }
    }
    activeDispatchers.clear();

    closeServerSocket();
    shutdownExecutor();
    closeTools();
    JShellSessionManagers.shutdown(context);

    // Close debugger notification registration
    if (debuggerNotificationRegistration != null) {
      try {
        debuggerNotificationRegistration.close();
      } catch (Exception e) {
        logger.warn("Error closing debugger notification registration", e);
      }
    }

    logger.info("MCP server stopped");
  }

  /**
   * Closes the server socket.
   */
  private void closeServerSocket() {
    try {
      if (serverSocket != null && !serverSocket.isClosed()) {
        serverSocket.close();
      }
    } catch (Exception e) {
      logger.error("Error closing server socket", e);
    }
  }

  /**
   * Shuts down the executor service and waits for termination.
   *
   * <p>
   * Waits up to 10 seconds for graceful shutdown. If tasks don't complete in
   * time, forces shutdown and logs any dropped tasks.
   */
  private void shutdownExecutor() {
    if (executorService != null) {
      executorService.shutdown();
      try {
        if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
          logger.warn("Executor did not terminate gracefully within 10 seconds, forcing shutdown");
          List<Runnable> droppedTasks = executorService.shutdownNow();
          if (!droppedTasks.isEmpty()) {
            logger.warn("Dropped {} tasks during forced shutdown", droppedTasks.size());
          }
        }
      } catch (InterruptedException e) {
        logger.warn("Interrupted while waiting for executor shutdown");
        executorService.shutdownNow();
        Thread.currentThread().interrupt();
      }
      executorService = null;
    }
  }

  /**
   * Closes all tools and releases their resources.
   */
  private void closeTools() {
    for (MCPTool tool : tools) {
      try {
        tool.close();
      } catch (Exception e) {
        logger.error("Error closing tool: " + tool.getToolName(), e);
      }
    }
  }

  /**
   * Checks if the server is currently running.
   * 
   * @return true if the server is running, false otherwise
   */
  public boolean isRunning() {
    return running;
  }

  /**
   * Exception thrown when a requested method is not found.
   */
  private static class MethodNotFoundException extends Exception {
    private static final long serialVersionUID = 1L;

    public MethodNotFoundException(String message) {
      super(message);
    }
  }

  /**
   * Exception thrown when method parameters are invalid.
   */
  private static class InvalidParametersException extends Exception {
    private static final long serialVersionUID = 1L;

    public InvalidParametersException(String message) {
      super(message);
    }
  }

  private synchronized ExecutorService ensureExecutor() {
    if (!running) {
      throw new IllegalStateException("Server is not running - cannot create executor");
    }
    if (executorService == null || executorService.isShutdown() || executorService.isTerminated()) {
      executorService = Executors.newCachedThreadPool();
    }
    return executorService;
  }
}
