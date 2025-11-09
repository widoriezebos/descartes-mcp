package com.bitsapplied.descartes.resources;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.bitsapplied.descartes.util.QueryParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP Resource that provides access to application-specific objects stored in
 * the context map. This allows applications to expose their internal state and
 * objects for inspection.
 */
public class ApplicationContextResource implements MCPResourceHandler {
  private static final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, Object> context;

  /**
   * Creates a new ApplicationContextResource with the given context map.
   * 
   * @param context the application context map containing objects to expose
   */
  public ApplicationContextResource(Map<String, Object> context) {
    this.context = context != null ? context : new HashMap<>();
  }

  @Override
  public String getUriPath() {
    return "context";
  }

  @Override
  public String getName() {
    return "Application Context";
  }

  @Override
  public String getDescription() {
    return "Application runtime context inspector providing access to registered application objects and state. "
        + "Exposes application-specific components stored in the context map, allowing inspection of object types, "
        + "fields, methods, and current values. Supports listing all context keys, inspecting individual objects, "
        + "and exploring object relationships. Parameters: 'key' (specific context key), 'depth' (inspection depth). "
        + "Useful for understanding application architecture, debugging state issues, and runtime introspection.";
  }

  @Override
  public String getMimeType() {
    return "application/json";
  }

  @Override
  public String handleRequest(QueryParams queryParams) throws MCPResource.ResourceException {
    try {
      String action = queryParams.get("action", "list");

      switch (action) {
      case "list":
        return listContextKeys();
      case "inspect":
        String key = queryParams.get("key", "");
        int depth = getIntParam(queryParams, "depth", 2);
        return inspectObject(key, depth);
      case "type":
        String typeKey = queryParams.get("key", "");
        return getObjectType(typeKey);
      case "methods":
        String methodKey = queryParams.get("key", "");
        return getObjectMethods(methodKey);
      case "fields":
        String fieldKey = queryParams.get("key", "");
        return getObjectFields(fieldKey);
      default:
        throw new MCPResource.ResourceException("Unknown action: " + action);
      }
    } catch (MCPResource.ResourceException e) {
      throw e;
    } catch (Exception e) {
      throw new MCPResource.ResourceException("Error handling context request", e);
    }
  }

  private String listContextKeys() throws Exception {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode keysArray = result.putArray("keys");

    for (Map.Entry<String, Object> entry : context.entrySet()) {
      ObjectNode keyNode = keysArray.addObject();
      keyNode.put("key", entry.getKey());

      Object value = entry.getValue();
      if (value != null) {
        keyNode.put("type", value.getClass().getName());
        keyNode.put("simpleType", value.getClass().getSimpleName());
        keyNode.put("toString", truncateString(value.toString(), 100));

        // Add additional metadata
        if (value instanceof Collection) {
          keyNode.put("collectionSize", ((Collection<?>) value).size());
        } else if (value instanceof Map) {
          keyNode.put("mapSize", ((Map<?, ?>) value).size());
        } else if (value.getClass().isArray()) {
          keyNode.put("arrayLength", java.lang.reflect.Array.getLength(value));
        }
      } else {
        keyNode.put("type", "null");
      }
    }

    result.put("totalKeys", context.size());
    result.put("timestamp", System.currentTimeMillis());

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String inspectObject(String key, int depth) throws Exception {
    if (key == null || key.isEmpty()) {
      throw new MCPResource.ResourceException("Key parameter is required");
    }

    if (!context.containsKey(key)) {
      throw new MCPResource.ResourceException("Key not found in context: " + key);
    }

    ObjectNode result = mapper.createObjectNode();
    result.put("key", key);

    Object value = context.get(key);
    if (value == null) {
      result.put("value", "null");
      result.put("type", "null");
    } else {
      result.put("type", value.getClass().getName());
      result.put("simpleType", value.getClass().getSimpleName());
      result.set("value", inspectValue(value, depth, Collections.newSetFromMap(new IdentityHashMap<>())));
    }

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private ObjectNode inspectValue(Object value, int depth, Set<Object> visited) {
    ObjectNode node = mapper.createObjectNode();

    if (value == null) {
      node.put("value", "null");
      return node;
    }

    if (depth <= 0 || visited.contains(value)) {
      node.put("value", truncateString(value.toString(), 200));
      node.put("truncated", true);
      return node;
    }

    visited.add(value);
    Class<?> clazz = value.getClass();

    // Handle primitives and common types
    if (isPrimitiveOrWrapper(clazz) || value instanceof String) {
      node.put("value", value.toString());
    } else if (value instanceof Collection) {
      ArrayNode arrayNode = node.putArray("elements");
      Collection<?> collection = (Collection<?>) value;
      int count = 0;
      for (Object item : collection) {
        if (count++ >= 100) { // Limit to first 100 elements
          node.put("truncated", true);
          break;
        }
        arrayNode.add(inspectValue(item, depth - 1, visited));
      }
      node.put("size", collection.size());
      node.put("type", "collection");
    } else if (value instanceof Map) {
      ObjectNode mapNode = node.putObject("entries");
      Map<?, ?> map = (Map<?, ?>) value;
      int count = 0;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (count++ >= 100) { // Limit to first 100 entries
          node.put("truncated", true);
          break;
        }
        String keyStr = entry.getKey() != null ? entry.getKey().toString() : "null";
        mapNode.set(keyStr, inspectValue(entry.getValue(), depth - 1, visited));
      }
      node.put("size", map.size());
      node.put("type", "map");
    } else if (clazz.isArray()) {
      ArrayNode arrayNode = node.putArray("elements");
      int length = java.lang.reflect.Array.getLength(value);
      int limit = Math.min(length, 100); // Limit to first 100 elements
      for (int i = 0; i < limit; i++) {
        Object element = java.lang.reflect.Array.get(value, i);
        arrayNode.add(inspectValue(element, depth - 1, visited));
      }
      node.put("length", length);
      node.put("type", "array");
      if (limit < length) {
        node.put("truncated", true);
      }
    } else {
      // For other objects, inspect their fields
      ObjectNode fieldsNode = node.putObject("fields");
      Field[] fields = clazz.getDeclaredFields();

      for (Field field : fields) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue; // Skip static fields
        }

        try {
          field.setAccessible(true);
          Object fieldValue = field.get(value);
          fieldsNode.set(field.getName(), inspectValue(fieldValue, depth - 1, visited));
        } catch (Exception e) {
          fieldsNode.put(field.getName(), "Error accessing: " + e.getMessage());
        }
      }

      node.put("className", clazz.getName());
      node.put("toString", truncateString(value.toString(), 200));
      node.put("type", "object");
    }

    visited.remove(value);
    return node;
  }

  private String getObjectType(String key) throws Exception {
    if (key == null || key.isEmpty()) {
      throw new MCPResource.ResourceException("Key parameter is required");
    }

    if (!context.containsKey(key)) {
      throw new MCPResource.ResourceException("Key not found in context: " + key);
    }

    ObjectNode result = mapper.createObjectNode();
    result.put("key", key);

    Object value = context.get(key);
    if (value == null) {
      result.put("type", "null");
    } else {
      Class<?> clazz = value.getClass();
      result.put("className", clazz.getName());
      result.put("simpleName", clazz.getSimpleName());
      result.put("packageName", clazz.getPackage() != null ? clazz.getPackage().getName() : "");
      result.put("isArray", clazz.isArray());
      result.put("isPrimitive", clazz.isPrimitive());
      result.put("isInterface", clazz.isInterface());
      result.put("isEnum", clazz.isEnum());
      result.put("isAnnotation", clazz.isAnnotation());

      // Superclass hierarchy
      ArrayNode hierarchyArray = result.putArray("hierarchy");
      Class<?> current = clazz.getSuperclass();
      while (current != null && current != Object.class) {
        hierarchyArray.add(current.getName());
        current = current.getSuperclass();
      }

      // Interfaces
      ArrayNode interfacesArray = result.putArray("interfaces");
      for (Class<?> iface : clazz.getInterfaces()) {
        interfacesArray.add(iface.getName());
      }
    }

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String getObjectMethods(String key) throws Exception {
    if (key == null || key.isEmpty()) {
      throw new MCPResource.ResourceException("Key parameter is required");
    }

    if (!context.containsKey(key)) {
      throw new MCPResource.ResourceException("Key not found in context: " + key);
    }

    ObjectNode result = mapper.createObjectNode();
    result.put("key", key);

    Object value = context.get(key);
    if (value == null) {
      result.put("type", "null");
      result.putArray("methods");
    } else {
      Class<?> clazz = value.getClass();
      result.put("className", clazz.getName());

      ArrayNode methodsArray = result.putArray("methods");
      Method[] methods = clazz.getMethods();

      for (Method method : methods) {
        // Skip Object class methods unless overridden
        if (method.getDeclaringClass() == Object.class && !isOverridden(method, clazz)) {
          continue;
        }

        ObjectNode methodNode = methodsArray.addObject();
        methodNode.put("name", method.getName());
        methodNode.put("returnType", method.getReturnType().getSimpleName());
        methodNode.put("declaringClass", method.getDeclaringClass().getName());
        methodNode.put("modifiers", Modifier.toString(method.getModifiers()));

        ArrayNode paramsArray = methodNode.putArray("parameters");
        for (Class<?> paramType : method.getParameterTypes()) {
          paramsArray.add(paramType.getSimpleName());
        }

        ArrayNode exceptionsArray = methodNode.putArray("exceptions");
        for (Class<?> exceptionType : method.getExceptionTypes()) {
          exceptionsArray.add(exceptionType.getSimpleName());
        }
      }

      result.put("methodCount", methodsArray.size());
    }

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String getObjectFields(String key) throws Exception {
    if (key == null || key.isEmpty()) {
      throw new MCPResource.ResourceException("Key parameter is required");
    }

    if (!context.containsKey(key)) {
      throw new MCPResource.ResourceException("Key not found in context: " + key);
    }

    ObjectNode result = mapper.createObjectNode();
    result.put("key", key);

    Object value = context.get(key);
    if (value == null) {
      result.put("type", "null");
      result.putArray("fields");
    } else {
      Class<?> clazz = value.getClass();
      result.put("className", clazz.getName());

      ArrayNode fieldsArray = result.putArray("fields");
      Field[] fields = clazz.getDeclaredFields();

      for (Field field : fields) {
        ObjectNode fieldNode = fieldsArray.addObject();
        fieldNode.put("name", field.getName());
        fieldNode.put("type", field.getType().getSimpleName());
        fieldNode.put("modifiers", Modifier.toString(field.getModifiers()));
        fieldNode.put("declaringClass", field.getDeclaringClass().getName());

        // Try to get field value
        if (!Modifier.isStatic(field.getModifiers())) {
          try {
            field.setAccessible(true);
            Object fieldValue = field.get(value);
            if (fieldValue != null) {
              fieldNode.put("value", truncateString(fieldValue.toString(), 100));
            } else {
              fieldNode.put("value", "null");
            }
          } catch (Exception e) {
            fieldNode.put("value", "Error accessing: " + e.getMessage());
          }
        }
      }

      result.put("fieldCount", fieldsArray.size());
    }

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private boolean isPrimitiveOrWrapper(Class<?> clazz) {
    return clazz.isPrimitive() || clazz == Boolean.class || clazz == Byte.class || clazz == Character.class
        || clazz == Short.class || clazz == Integer.class || clazz == Long.class || clazz == Float.class
        || clazz == Double.class;
  }

  private boolean isOverridden(Method method, Class<?> clazz) {
    try {
      Method overriddenMethod = clazz.getMethod(method.getName(), method.getParameterTypes());
      return overriddenMethod.getDeclaringClass() != Object.class;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private String truncateString(String str, int maxLength) {
    if (str == null) {
      return "null";
    }
    if (str.length() <= maxLength) {
      return str;
    }
    return str.substring(0, maxLength) + "...";
  }

  private int getIntParam(QueryParams params, String key, int defaultValue) {
    String value = params.get(key);
    if (value != null) {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        // Fall through to default
      }
    }
    return defaultValue;
  }
}
