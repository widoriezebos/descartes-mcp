package com.bitsapplied.descartes.tools;

import java.util.Map;

/**
 * Base interface for MCP (Model Context Protocol) tools. All MCP tools must
 * implement this interface to be registered with the MCP server.
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
   * Executes the tool with the given arguments.
   * 
   * @param arguments the tool arguments
   * @return the tool execution result as a string
   * @throws Exception if the tool execution fails
   */
  String executeTool(Map<String, Object> arguments) throws Exception;

  /**
   * Closes the tool and releases any resources. Default implementation does
   * nothing.
   */
  @Override
  default void close() throws Exception {
    // Default implementation does nothing
  }
}