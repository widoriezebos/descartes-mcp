package com.bitsapplied.descartes.resources;

import java.util.Map;

import com.bitsapplied.descartes.util.QueryParams;

/**
 * Interface for individual MCP resource handlers.
 * <p>
 * Each resource handler represents a specific endpoint that can be accessed via
 * the MCP protocol.
 */
public interface MCPResourceHandler {

  /**
   * @return the unique URI path for this resource (e.g., "memory/find")
   */
  String getUriPath();

  /**
   * @return the display name for this resource
   */
  String getName();

  /**
   * @return a human-readable description of what this resource does
   */
  String getDescription();

  /**
   * @return the MIME type of the response content
   */
  String getMimeType();

  /**
   * Process a request to this resource.
   * 
   * @param queryParams the parsed query parameters from the URI
   * @return the response as a string (typically JSON)
   * @throws MCPResource.ResourceException if there's an error processing the
   *                                       request
   */
  String handleRequest(QueryParams queryParams) throws MCPResource.ResourceException;

  /**
   * @return the resource metadata as a map for listing resources
   */
  default Map<String, Object> getResourceMetadata() {
    return Map.of("uri", getUriPath(), "name", getName(), "description", getDescription(), "mimeType", getMimeType());
  }
}