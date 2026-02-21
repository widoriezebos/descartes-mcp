package com.bitsapplied.descartes.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;

/**
 * Utility for JSON serialization with optional blacklist/whitelist and
 * empty-value handling.
 */
public final class JsonUtils {

  private static final String FILTER_ID = "dynamicFilter";

  /**
   * MixIn for applying dynamic property filters.
   */
  @JsonFilter(FILTER_ID)
  private interface DynamicFilterMixIn {
  }

  // Shared mappers: thread-safe after configuration
  private static final ObjectMapper MAPPER_NULL;
  private static final ObjectMapper MAPPER_EMPTY;
  static {
    // Default mapper: drop nulls, but keep empty collections as []
    MAPPER_NULL = new ObjectMapper();
    MAPPER_NULL.setSerializationInclusion(Include.NON_NULL);
    MAPPER_NULL.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    // Mapper to drop empty values
    MAPPER_EMPTY = new ObjectMapper();
    MAPPER_EMPTY.setSerializationInclusion(Include.NON_EMPTY);
    MAPPER_EMPTY.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
  }

  // Writers configured for pretty-print (INDENT_OUTPUT)
  private static final ObjectWriter WRITER_NULL = MAPPER_NULL.writer(SerializationFeature.INDENT_OUTPUT);
  private static final ObjectWriter WRITER_EMPTY = MAPPER_EMPTY.writer(SerializationFeature.INDENT_OUTPUT);

  // Prevent instantiation
  private JsonUtils() {
  }

  /**
   * Simple serialization, dropping nulls &amp; keeping empty collections as JSON
   * [].
   */
  public static String toJSON(Object object) {
    return toJSON(object, null, false);
  }

  /**
   * Serialization with blacklist, dropping nulls &amp; keeping empty collections
   * as
   * JSON [].
   */
  public static String toJSON(Object object, Set<String> blacklist) {
    return toJSON(object, blacklist, false);
  }

  /**
   * Serialization with blacklist and optional empty-value removal.
   *
   * @param object    the object to serialize
   * @param blacklist property names to exclude
   * @param noEmpties if true, also drop empty strings/collections
   */
  public static String toJSON(Object object, Set<String> blacklist, boolean noEmpties) {
    if (object == null) {
      throw new NullPointerException("object to serialize is null");
    }
    try {
      // No filtering: use shared writer
      if (blacklist == null || blacklist.isEmpty()) {
        return (noEmpties ? WRITER_EMPTY : WRITER_NULL).writeValueAsString(object);
      }

      // Build a filter provider for this call
      FilterProvider provider = new SimpleFilterProvider().addFilter(FILTER_ID,
          SimpleBeanPropertyFilter.serializeAllExcept(blacklist));

      // Copy base mapper to avoid mutating shared state, add mix-in
      ObjectMapper mapper = (noEmpties ? MAPPER_EMPTY : MAPPER_NULL).copy().addMixIn(Object.class,
          DynamicFilterMixIn.class);

      // Create a writer with the filter and pretty-print
      ObjectWriter writer = mapper.writer(provider).with(SerializationFeature.INDENT_OUTPUT);

      return writer.writeValueAsString(object);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize object to JSON", e);
    }
  }

  /**
   * Simple serialization with whitelist, dropping nulls &amp; keeping empty
   * collections as JSON [].
   */
  public static String toJSONWhitelisted(Object object, Set<String> whitelist) {
    return toJSONWhitelisted(object, whitelist, false);
  }

  /**
   * Serialization with whitelist and optional empty-value removal. Note: empty
   * whitelist will remove all fields.
   */
  public static String toJSONWhitelisted(Object object, Set<String> whitelist, boolean noEmpties) {
    if (object == null) {
      throw new NullPointerException("object to serialize is null");
    }
    try {
      ObjectMapper mapper = (noEmpties ? MAPPER_EMPTY : MAPPER_NULL);
      JsonNode root = mapper.valueToTree(object);

      // Prune tree if whitelist is provided (even if empty set)
      if (whitelist != null) {
        if (root.isObject()) {
          removeNonWhitelistedProperties((ObjectNode) root, "", whitelist);
        } else if (root.isArray()) {
          ArrayNode arr = (ArrayNode) root;
          for (int i = arr.size() - 1; i >= 0; i--) {
            JsonNode el = arr.get(i);
            if (el.isObject()) {
              removeNonWhitelistedProperties((ObjectNode) el, "", whitelist);
            } else {
              arr.remove(i);
            }
          }
        }
      }

      return (noEmpties ? WRITER_EMPTY : WRITER_NULL).writeValueAsString(root);
    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize object to JSON with whitelist", e);
    }
  }

  // ----- Internal tree-pruning for whitelist -----
  private static void removeNonWhitelistedProperties(ObjectNode node, String currentPath, Set<String> whitelist) {
    Iterator<String> fields = node.fieldNames();
    List<String> toRemove = new ArrayList<>();

    while (fields.hasNext()) {
      String name = fields.next();
      String fullPath = currentPath.isEmpty() ? name : currentPath + "." + name;

      boolean keep = false;
      for (String path : whitelist) {
        if (path.equals(fullPath) || path.startsWith(fullPath + ".")) {
          keep = true;
          break;
        }
      }

      if (keep) {
        JsonNode child = node.get(name);
        if (child.isObject()) {
          removeNonWhitelistedProperties((ObjectNode) child, fullPath, whitelist);
        } else if (child.isArray()) {
          ArrayNode arr = (ArrayNode) child;
          for (int i = arr.size() - 1; i >= 0; i--) {
            JsonNode el = arr.get(i);
            if (el.isObject()) {
              removeNonWhitelistedProperties((ObjectNode) el, fullPath, whitelist);
            }
          }
        }
      } else {
        toRemove.add(name);
      }
    }

    toRemove.forEach(node::remove);
  }
}
