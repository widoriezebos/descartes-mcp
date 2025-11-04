package com.bitsapplied.descartes.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base interface for MCP (Model Context Protocol) tools. All MCP tools must
 * implement this interface to be registered with the MCP server.
 *
 * <p>
 * <b>BREAKING CHANGE (Phase 1a):</b> The execution method has been changed from
 * synchronous {@code String executeTool(Map)} to asynchronous
 * {@code CompletableFuture<ToolResponse> executeAsync(Map)} to support
 * non-blocking operations and structured error handling.
 *
 * <p>
 * Migration pattern for existing tools:
 * 
 * <pre>{@code
 * @Override
 * public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
 *   return CompletableFuture.supplyAsync(() -> {
 *     try {
 *       String result = performOperation(arguments); // Existing synchronous logic
 *       return ToolResponse.success(result);
 *     } catch (Exception e) {
 *       return ToolResponse.error(9999, e.getMessage());
 *     }
 *   }, executorService); // Use appropriate executor
 * }
 * }</pre>
 */
public interface MCPTool extends AutoCloseable {

  /**
   * Gets the name of the tool as exposed to MCP clients.
   *
   * @return the tool name
   */
  String getToolName();

  /**
   * Gets a human-readable description of what the tool does.
   *
   * @return the tool description
   */
  String getToolDescription();

  /**
   * Gets the JSON schema for the tool's input parameters.
   *
   * @return the tool schema as a Map
   */
  Map<String, Object> getToolSchema();

  /**
   * Executes the tool asynchronously with the given arguments.
   *
   * <p>
   * This method should return immediately with a CompletableFuture that will be
   * completed when the tool execution finishes. The future should never complete
   * exceptionally - instead, errors should be represented as
   * {@link ToolResponse.Error} results.
   *
   * <p>
   * <b>Threading:</b> Implementations should execute on an appropriate executor
   * service (not on the calling thread). Debugger tools MUST use the
   * single-threaded debugger executor to ensure thread safety with JDI.
   *
   * @param arguments the tool arguments
   * @return a CompletableFuture that completes with the tool result
   */
  CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments);

  /**
   * Closes the tool and releases any resources. Default implementation does
   * nothing.
   */
  @Override
  default void close() throws Exception {
    // Default implementation does nothing
  }
}
