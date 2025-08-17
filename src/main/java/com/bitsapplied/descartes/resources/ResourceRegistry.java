package com.bitsapplied.descartes.resources;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.util.QueryParams;

/**
 * Generic resource registry for MCP servers.
 * <p>
 * This class manages a registry of resource handlers and handles URI routing to
 * the appropriate handlers.
 */
public class ResourceRegistry implements MCPResource {
  private final Map<String, MCPResourceHandler> resources = new HashMap<>();
  private final String uriScheme;

  /**
   * Creates a new resource registry with the specified URI scheme.
   * 
   * @param uriScheme the URI scheme to use (e.g., "morpheus", "myapp")
   */
  public ResourceRegistry(String uriScheme) {
    this.uriScheme = uriScheme;
  }

  /**
   * Registers a resource handler.
   * 
   * @param resource the resource handler to register
   */
  public void registerResource(MCPResourceHandler resource) {
    resources.put(resource.getUriPath(), resource);
  }

  /**
   * Lists all available resources with their metadata.
   * 
   * @return a list of resource metadata maps
   */
  @Override
  public List<Map<String, Object>> listResources() {
    return resources.values().stream().map(resource -> {
      Map<String, Object> metadata = new HashMap<>(resource.getResourceMetadata());
      // Prepend the URI scheme if not already present
      String uri = (String) metadata.get("uri");
      if (uri != null && !uri.contains("://")) {
        metadata.put("uri", uriScheme + "://" + uri);
      }
      return metadata;
    }).collect(Collectors.toList());
  }

  /**
   * Reads a resource by its URI.
   * 
   * @param uri the resource URI (e.g., "scheme://path?query=test")
   * @return the resource content as a string
   * @throws ResourceException if the resource cannot be read
   */
  @Override
  public String readResource(String uri) throws ResourceException {
    String expectedPrefix = uriScheme + "://";
    if (!uri.startsWith(expectedPrefix)) {
      throw new ResourceException("Invalid URI scheme. Expected " + expectedPrefix);
    }

    String path = uri.substring(expectedPrefix.length());
    String[] parts = path.split("\\?", 2);
    String resourcePath = parts[0];
    String queryString = parts.length > 1 ? parts[1] : "";

    MCPResourceHandler resource = resources.get(resourcePath);
    if (resource == null) {
      throw new ResourceException("Unknown resource: " + resourcePath);
    }

    try {
      QueryParams queryParams = new QueryParams(queryString);
      return resource.handleRequest(queryParams);
    } catch (ResourceException e) {
      throw e;
    } catch (Exception e) {
      throw new ResourceException("Internal error: " + e.getMessage(), e);
    }
  }
}