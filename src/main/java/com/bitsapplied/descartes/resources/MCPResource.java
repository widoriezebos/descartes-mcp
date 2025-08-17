package com.bitsapplied.descartes.resources;

import java.util.List;
import java.util.Map;

/**
 * Interface for MCP resources that can be exposed through the MCP server.
 */
public interface MCPResource {

  /**
   * Lists all available resources.
   * 
   * @return a list of resource descriptions
   */
  List<Map<String, Object>> listResources();

  /**
   * Reads a specific resource by URI.
   * 
   * @param uri the resource URI
   * @return the resource content as a string
   * @throws ResourceException if the resource cannot be read
   */
  String readResource(String uri) throws ResourceException;

  /**
   * Exception thrown when a resource operation fails.
   */
  public static class ResourceException extends Exception {
    private static final long serialVersionUID = 1L;

    public ResourceException(String message) {
      super(message);
    }

    public ResourceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}