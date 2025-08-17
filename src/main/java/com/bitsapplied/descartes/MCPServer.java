package com.bitsapplied.descartes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.bitsapplied.descartes.resources.MCPResource;
import com.bitsapplied.descartes.settings.SettingsProvider;
import com.bitsapplied.descartes.tools.MCPTool;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Generic MCP (Model Context Protocol) server implementation. This server can
 * be used standalone or integrated into any application.
 * 
 * The server uses a generic context Map to allow tools and resources to access
 * application-specific objects without coupling to specific types.
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

    executorService.submit(this::acceptConnections);

    logger.info("MCP server started successfully on port {}", port);
  }

  /**
   * Continuously accepts client connections while the server is running.
   */
  private void acceptConnections() {
    while (running) {
      try {
        Socket clientSocket = serverSocket.accept();
        logger.info("New MCP client connected from {}", clientSocket.getRemoteSocketAddress());
        executorService.submit(() -> handleClient(clientSocket));
      } catch (Exception e) {
        if (running) {
          logger.error("Error accepting client connection", e);
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
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream()), true)) {

      processClientRequests(reader, writer);

    } catch (java.net.SocketException e) {
      // Normal disconnection - client closed connection
      logger.debug("Client disconnected: {}", e.getMessage());
    } catch (java.io.EOFException e) {
      // Normal EOF - client closed connection gracefully
      logger.debug("Client closed connection gracefully");
    } catch (Exception e) {
      logger.error("Error handling client", e);
    } finally {
      closeClientSocket(clientSocket);
    }
  }

  /**
   * Processes incoming requests from the client.
   */
  @SuppressWarnings("unchecked")
  private void processClientRequests(BufferedReader reader, PrintWriter writer) throws Exception {
    String line;
    while ((line = reader.readLine()) != null) {
      try {
        Map<String, Object> request = objectMapper.readValue(line, Map.class);
        Map<String, Object> response = handleRequest(request);
        writer.println(objectMapper.writeValueAsString(response));
      } catch (Exception e) {
        logger.error("Error handling request", e);
        Map<String, Object> errorResponse = buildErrorResponse(null, ERROR_INTERNAL,
            "Internal error: " + e.getMessage());
        writer.println(objectMapper.writeValueAsString(errorResponse));
      }
    }
  }

  /**
   * Safely closes the client socket connection.
   */
  private void closeClientSocket(Socket clientSocket) {
    try {
      clientSocket.close();
    } catch (Exception e) {
      logger.error("Error closing client socket", e);
    }
    logger.info("Client disconnected");
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
   * Handles the tools/call method.
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> handleToolsCall(Map<String, Object> params) throws InvalidParametersException {

    String toolName = (String) params.get("name");
    Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");

    MCPTool tool = findToolByName(toolName);
    if (tool == null) {
      throw new InvalidParametersException("Unknown tool: " + toolName);
    }

    try {
      String result = tool.executeTool(arguments);
      return Map.of("content", List.of(Map.of("type", "text", "text", result)));
    } catch (Exception e) {
      logger.error("Error executing tool: " + toolName, e);
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
        String resourceResult = resource.readResource(uri);
        return Map.of("contents", List.of(Map.of("uri", uri, "mimeType", "application/json", "text", resourceResult)));
      } catch (MCPResource.ResourceException e) {
        // Try next provider
        continue;
      }
    }

    throw new InvalidParametersException("Resource not found: " + uri);
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
    Map<String, Object> response = new HashMap<>();
    response.put("jsonrpc", JSONRPC_VERSION);
    if (id != null) {
      response.put("id", id);
    }
    response.put("error", Map.of("code", code, "message", message));
    return response;
  }

  /**
   * Gracefully stops the MCP server.
   */
  public void stop() {
    logger.info("Stopping MCP server");
    running = false;

    closeServerSocket();
    shutdownExecutor();
    closeTools();

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
   * Shuts down the executor service.
   */
  private void shutdownExecutor() {
    if (executorService != null) {
      executorService.shutdown();
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
}