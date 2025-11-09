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
  default ResourceReadResult readResourceDetailed(String uri) throws ResourceException {
    return new ResourceReadResult("application/json", readResource(uri));
  }

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

  /**
   * Exception indicating the requested resource could not be found.
   */
  public static final class ResourceNotFoundException extends ResourceException {
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
      super(message);
    }
  }

  /**
   * Immutable result object providing both MIME type and content for resource
   * reads.
   *
   * @param mimeType the MIME type of the content
   * @param content  the textual representation of the resource
   */
  public record ResourceReadResult(String mimeType, String content) {
  }
}
