package com.bitsapplied.descartes.tools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.util.EvalResult;
import com.bitsapplied.descartes.util.JShellService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP tool for inspecting objects via expressions evaluated from the context.
 * Evaluates expressions and provides detailed inspection.
 */
public class ObjectInspectorTool implements MCPTool {

  protected final JShellService jshellService;
  protected final ObjectMapper objectMapper = new ObjectMapper();
  protected final String contextVariableName;

  /**
   * Creates an ObjectInspectorTool with the specified context and variable name.
   *
   * @param context             the context map for JShell
   * @param contextVariableName the name of the context variable to use in
   *                            expressions (e.g., "context", "appContext")
   */
  public ObjectInspectorTool(Map<String, Object> context, String contextVariableName) {
    this.jshellService = new JShellService(context);
    this.contextVariableName = contextVariableName;
  }

  /**
   * Creates an ObjectInspectorTool with default "context" as the variable name.
   */
  public ObjectInspectorTool(Map<String, Object> context) {
    this(context, "context");
  }

  @Override
  public String getToolName() {
    return "object_inspector";
  }

  @Override
  public String getToolDescription() {
    return String.format(
        "Inspects objects by evaluating expressions starting from '%s'. Provides detailed information about object structure, fields, methods, and values.",
        contextVariableName);
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties",
        Map.of("expression",
            Map.of("type", "string", "description",
                String.format("Expression to evaluate starting with '%s' (e.g., '%s.get(\"key\")', '%s.toString()')",
                    contextVariableName, contextVariableName, contextVariableName)),
            "operation",
            Map.of("type", "string", "enum", List.of("inspect", "fields", "methods", "type", "value"), "description",
                "The inspection operation to perform", "default", "inspect"),
            "include_private",
            Map.of("type", "boolean", "description", "Include private fields/methods in inspection", "default", false),
            "max_depth",
            Map.of("type", "integer", "description", "Maximum depth for recursive inspection", "default", 2)),
        "required", List.of("expression"));
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        String expression = (String) arguments.get("expression");
        String operation = (String) arguments.getOrDefault("operation", "inspect");
        Boolean includePrivate = (Boolean) arguments.getOrDefault("include_private", false);
        Integer maxDepth = ((Number) arguments.getOrDefault("max_depth", 2)).intValue();

        if (expression == null || expression.isEmpty()) {
          throw new IllegalArgumentException("Expression is required");
        }

        // Ensure expression starts with the context variable for security
        if (!expression.trim().startsWith(contextVariableName)) {
          throw new IllegalArgumentException(
              String.format("Expression must start with '%s' for security reasons", contextVariableName));
        }

        Map<String, Object> result;

        try {
          // Evaluate the expression to get the object
          Object evaluatedObject = evaluateExpression(expression);

          if (evaluatedObject == null) {
            result = Map.of("status", "success", "expression", expression, "result", "null", "type", "null");
          } else {
            result = switch (operation) {
            case "inspect" -> inspectObject(evaluatedObject, expression, includePrivate, maxDepth);
            case "fields" -> getFields(evaluatedObject, expression, includePrivate);
            case "methods" -> getMethods(evaluatedObject, expression, includePrivate);
            case "type" -> getTypeInfo(evaluatedObject, expression);
            case "value" -> getValue(evaluatedObject, expression);
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
            };
          }
        } catch (Exception e) {
          // Include stack trace in error for debugging
          String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
          if (e.getCause() != null) {
            errorMsg += " | Cause: " + e.getCause().getMessage();
          }
          result = Map.of("status", "error", "expression", expression, "error",
              e.getClass().getSimpleName() + ": " + errorMsg);
        }

        return ToolResponse.success(objectMapper.writeValueAsString(result));
      } catch (Exception e) {
        return ToolResponse.error(9999, "Object inspection failed: " + e.getMessage());
      }
    });
  }

  /**
   * Evaluates the expression and returns the resulting object.
   *
   * WARNING: This implementation uses a static volatile field which has a
   * theoretical race condition when multiple tool instances evaluate
   * concurrently. This is a known limitation of the JShell-based evaluation
   * approach. The field is volatile for visibility, but does not prevent TOCTOU
   * races if: 1. Thread A evaluates expression X and writes result R1 to
   * lastInspectedObject 2. Thread B evaluates expression Y and writes result R2
   * to lastInspectedObject 3. Thread A reads lastInspectedObject and gets R2
   * instead of R1
   *
   * In practice, MCP tool invocations are typically sequential, making this
   * unlikely. A proper fix would require significant changes to the JShell
   * evaluation model.
   */
  protected Object evaluateExpression(String expression) throws Exception {
    // Store the result in a static field that we can access
    String storeCode = String.format("""
        com.bitsapplied.descartes.tools.ObjectInspectorTool.lastInspectedObject = %s;
        "stored"
        """, expression);

    EvalResult evalResult = jshellService.eval(storeCode);

    // Check for errors in the evaluation
    if (!evalResult.getErr().isEmpty()) {
      throw new RuntimeException("Failed to evaluate expression: " + evalResult.getErr());
    }

    // Check for exceptions in the events
    for (EvalResult.SnippetResult event : evalResult.getEvents()) {
      if (event.getExceptionType() != null) {
        throw new RuntimeException(
            "Evaluation exception: " + event.getExceptionType() + ": " + event.getExceptionMessage());
      }
    }

    return lastInspectedObject;
  }

  /**
   * Static volatile field to hold the last inspected object from JShell.
   *
   * WARNING - KNOWN RACE CONDITION: This field is volatile for visibility across
   * threads, but does NOT prevent race conditions if multiple ObjectInspectorTool
   * instances evaluate expressions concurrently.
   *
   * Volatile ensures that writes are immediately visible to other threads, but
   * there's still a time-of-check-to-time-of-use window between when JShell
   * writes the value and when evaluateExpression() reads it. If another thread's
   * evaluation completes in that window, the wrong result will be returned.
   *
   * This is an inherent limitation of using JShell with static shared state. A
   * proper solution would require either: - Synchronizing all tool invocations
   * (poor performance) - Using thread-local storage (complex JShell integration)
   * - Redesigning the evaluation mechanism entirely
   *
   * In practice, MCP servers typically process tool calls sequentially, making
   * this race condition unlikely to occur. However, users should be aware that
   * concurrent object inspection is not thread-safe.
   */
  public static volatile Object lastInspectedObject;

  private Map<String, Object> inspectObject(Object obj, String expression, boolean includePrivate, int maxDepth) {
    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("expression", expression);

    Class<?> clazz = obj.getClass();
    result.put("type", clazz.getName());
    result.put("simple_type", clazz.getSimpleName());

    // Add value representation
    String valueStr = getValueString(obj);
    result.put("value", valueStr);

    // Add class hierarchy
    List<String> interfaces = new ArrayList<>();
    for (Class<?> iface : clazz.getInterfaces()) {
      interfaces.add(iface.getName());
    }
    result.put("interfaces", interfaces);

    Class<?> superClass = clazz.getSuperclass();
    if (superClass != null) {
      result.put("superclass", superClass.getName());
    }

    // Add fields if depth > 0
    if (maxDepth > 0) {
      List<Map<String, Object>> fields = new ArrayList<>();
      for (Field field : clazz.getDeclaredFields()) {
        if (!includePrivate && Modifier.isPrivate(field.getModifiers())) {
          continue;
        }
        Map<String, Object> fieldInfo = new HashMap<>();
        fieldInfo.put("name", field.getName());
        fieldInfo.put("type", field.getType().getName());
        fieldInfo.put("modifiers", Modifier.toString(field.getModifiers()));

        // Try to get field value
        try {
          field.setAccessible(true);
          Object fieldValue = field.get(obj);
          if (fieldValue != null && maxDepth > 1) {
            // For complex objects, recurse with reduced depth
            if (!isPrimitive(fieldValue)) {
              fieldInfo.put("value",
                  inspectObject(fieldValue, expression + "." + field.getName(), includePrivate, maxDepth - 1));
            } else {
              fieldInfo.put("value", getValueString(fieldValue));
            }
          } else {
            fieldInfo.put("value", fieldValue == null ? "null" : getValueString(fieldValue));
          }
        } catch (Exception e) {
          fieldInfo.put("value", "Error accessing: " + e.getMessage());
        }
        fields.add(fieldInfo);
      }
      result.put("fields", fields);
    }

    // Add method count
    Method[] methods = includePrivate ? clazz.getDeclaredMethods() : clazz.getMethods();
    result.put("method_count", methods.length);

    return result;
  }

  private Map<String, Object> getFields(Object obj, String expression, boolean includePrivate) {
    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("expression", expression);
    result.put("type", obj.getClass().getName());

    List<Map<String, Object>> fields = new ArrayList<>();
    Field[] declaredFields = obj.getClass().getDeclaredFields();

    for (Field field : declaredFields) {
      if (!includePrivate && Modifier.isPrivate(field.getModifiers())) {
        continue;
      }

      Map<String, Object> fieldInfo = new HashMap<>();
      fieldInfo.put("name", field.getName());
      fieldInfo.put("type", field.getType().getName());
      fieldInfo.put("modifiers", Modifier.toString(field.getModifiers()));

      try {
        field.setAccessible(true);
        Object value = field.get(obj);
        fieldInfo.put("value", value == null ? "null" : getValueString(value));
      } catch (Exception e) {
        fieldInfo.put("value", "Error: " + e.getMessage());
      }

      fields.add(fieldInfo);
    }

    result.put("fields", fields);
    result.put("field_count", fields.size());
    return result;
  }

  private Map<String, Object> getMethods(Object obj, String expression, boolean includePrivate) {
    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("expression", expression);
    result.put("type", obj.getClass().getName());

    List<Map<String, Object>> methods = new ArrayList<>();
    Method[] declaredMethods = includePrivate ? obj.getClass().getDeclaredMethods() : obj.getClass().getMethods();

    for (Method method : declaredMethods) {
      if (!includePrivate && Modifier.isPrivate(method.getModifiers())) {
        continue;
      }

      Map<String, Object> methodInfo = new HashMap<>();
      methodInfo.put("name", method.getName());
      methodInfo.put("return_type", method.getReturnType().getName());
      methodInfo.put("modifiers", Modifier.toString(method.getModifiers()));

      List<String> paramTypes = new ArrayList<>();
      for (Class<?> paramType : method.getParameterTypes()) {
        paramTypes.add(paramType.getName());
      }
      methodInfo.put("parameters", paramTypes);

      methods.add(methodInfo);
    }

    result.put("methods", methods);
    result.put("method_count", methods.size());
    return result;
  }

  private Map<String, Object> getTypeInfo(Object obj, String expression) {
    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("expression", expression);

    Class<?> clazz = obj.getClass();
    result.put("type", clazz.getName());
    result.put("simple_type", clazz.getSimpleName());
    result.put("package", clazz.getPackage() != null ? clazz.getPackage().getName() : "");
    result.put("is_array", clazz.isArray());
    result.put("is_interface", clazz.isInterface());
    result.put("is_enum", clazz.isEnum());
    result.put("is_annotation", clazz.isAnnotation());
    result.put("is_primitive", clazz.isPrimitive());

    // Add interfaces
    List<String> interfaces = new ArrayList<>();
    for (Class<?> iface : clazz.getInterfaces()) {
      interfaces.add(iface.getName());
    }
    result.put("interfaces", interfaces);

    // Add superclass
    Class<?> superClass = clazz.getSuperclass();
    if (superClass != null) {
      result.put("superclass", superClass.getName());
    }

    return result;
  }

  private Map<String, Object> getValue(Object obj, String expression) {
    Map<String, Object> result = new HashMap<>();
    result.put("status", "success");
    result.put("expression", expression);
    result.put("type", obj.getClass().getName());
    result.put("value", getValueString(obj));
    return result;
  }

  private String getValueString(Object obj) {
    if (obj == null) {
      return "null";
    }
    if (obj instanceof String) {
      String str = (String) obj;
      if (str.length() > 1000) {
        return str.substring(0, 1000) + "... (truncated)";
      }
      return str;
    }
    if (obj instanceof Number || obj instanceof Boolean || obj instanceof Character) {
      return obj.toString();
    }
    if (obj.getClass().isArray()) {
      return "Array[" + java.lang.reflect.Array.getLength(obj) + "]";
    }
    try {
      String str = obj.toString();
      if (str.length() > 1000) {
        return str.substring(0, 1000) + "... (truncated)";
      }
      return str;
    } catch (Exception e) {
      return obj.getClass().getSimpleName() + "@" + Integer.toHexString(obj.hashCode());
    }
  }

  private boolean isPrimitive(Object obj) {
    return obj instanceof Number || obj instanceof Boolean || obj instanceof Character || obj instanceof String;
  }

  @Override
  public void close() {
    try {
      jshellService.close();
    } catch (Exception ignored) {
    }
  }
}