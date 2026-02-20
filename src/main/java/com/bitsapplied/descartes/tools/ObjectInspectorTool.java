package com.bitsapplied.descartes.tools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.bitsapplied.descartes.util.EvalResult;
import com.bitsapplied.descartes.util.JShellService;

/**
 * MCP tool for inspecting objects via expressions evaluated from the context.
 * Evaluates expressions and provides detailed inspection.
 */
public class ObjectInspectorTool implements MCPTool {

  /**
   * Strips leading Java type casts and grouping parentheses to find the root
   * identifier. Matches patterns like: ((TypeName) , (pkg.Type<Generic>) ,
   * bare opening parens — in any order.
   */
  private static final Pattern CAST_PREFIX_PATTERN =
      Pattern.compile("^(?:\\(\\s*[\\w.$\\[\\]<>,?\\s]+\\)\\s*|\\(\\s*)*");

  protected final JShellService jshellService;
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
    Map<String, Object> properties = new HashMap<>();
    properties.put("expression",
        Map.of("type", "string", "description",
            String.format("Expression to evaluate starting with '%s' (e.g., '%s.get(\"key\")')", contextVariableName,
                contextVariableName)));
    properties.put("operation",
        Map.of("type", "string", "enum", List.of("inspect", "fields", "methods", "type", "value"), "description",
            "Inspection operation to perform", "default", "inspect"));
    properties.put("include_private",
        Map.of("type", "boolean", "description", "Include private members in inspection", "default", false));
    properties.put("max_depth",
        Map.of("type", "integer", "minimum", 0, "maximum", 10, "description", "Maximum recursion depth", "default", 2));

    Map<String, Object> schema = new HashMap<>();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("properties", properties);
    schema.put("required", List.of("expression"));
    schema.put("description",
        "Inspect objects via JShell expressions scoped to the shared context. Expressions must start with the configured context variable.");
    return schema;
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

        // Ensure expression is rooted in the context variable for security.
        // Supports cast-wrapped expressions like ((Type) context.get("x")).method()
        // and grouping-paren expressions like (context).get("x").
        String trimmed = expression.trim();
        String castStripped = CAST_PREFIX_PATTERN.matcher(trimmed).replaceFirst("");
        String parenStripped = trimmed.replaceAll("^\\(+\\s*", "");
        if (!isRootedInContextVariable(castStripped)
            && !isRootedInContextVariable(parenStripped)) {
          throw new IllegalArgumentException(
              String.format("Expression must be rooted in '%s' for security reasons",
                  contextVariableName));
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

        return ToolResponse.successJson(result);
      } catch (IllegalArgumentException e) {
        return ToolResponse.validationError(e.getMessage());
      } catch (Exception e) {
        return ToolResponse.executionFailed("Object inspection failed: " + e.getMessage());
      }
    });
  }

  private boolean isRootedInContextVariable(String rootExpression) {
    if (!rootExpression.startsWith(contextVariableName)) {
      return false;
    }
    // Reject longer identifiers like "contextFake" — the char after the
    // variable name must not be a Java identifier part (should be '.', ')', etc.)
    if (rootExpression.length() > contextVariableName.length()
        && Character.isJavaIdentifierPart(
            rootExpression.charAt(contextVariableName.length()))) {
      return false;
    }
    return true;
  }

  /**
   * Evaluates the expression and returns the resulting object.
   *
   * This implementation uses a token-based approach with ConcurrentHashMap to
   * safely transfer objects from JShell's evaluation context. Each evaluation
   * gets a unique UUID token, eliminating race conditions that existed with the
   * previous static volatile field approach.
   *
   * The flow is: 1. Generate unique token for this evaluation 2. JShell evaluates
   * expression and stores result with token in map 3. Retrieve and remove result
   * using the token
   *
   * This approach is fully thread-safe and allows concurrent evaluations.
   *
   * Note: Since ConcurrentHashMap doesn't support null values, we use a sentinel
   * object to represent null results.
   */
  protected Object evaluateExpression(String expression) throws Exception {
    // Generate unique token for this evaluation
    String token = UUID.randomUUID().toString();

    // Store the result with the token in the concurrent map
    // We wrap null values in a sentinel since ConcurrentHashMap doesn't allow nulls
    String storeCode = String.format("""
        Object __evalResult = %s;
        Object __wrapped = (__evalResult == null)
            ? com.bitsapplied.descartes.tools.ObjectInspectorTool.NULL_SENTINEL
            : __evalResult;
        com.bitsapplied.descartes.tools.ObjectInspectorTool.inspectionResults.put("%s", __wrapped);
        "stored"
        """, expression, token);

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

    // Retrieve and remove the result (cleanup happens automatically)
    Object result = inspectionResults.remove(token);

    // Unwrap sentinel back to null
    if (result == NULL_SENTINEL) {
      return null;
    }

    return result;
  }

  /**
   * Sentinel object used to represent null values in the inspectionResults map.
   *
   * ConcurrentHashMap does not allow null keys or values, so we use this sentinel
   * to represent null evaluation results. When JShell evaluates an expression
   * that returns null, we store this sentinel instead, and unwrap it back to null
   * when retrieving the result.
   */
  public static final Object NULL_SENTINEL = new Object() {
    @Override
    public String toString() {
      return "NULL_SENTINEL";
    }
  };

  /**
   * Thread-safe map for storing inspection results keyed by unique tokens.
   *
   * This ConcurrentHashMap eliminates the race condition that existed with the
   * previous static volatile field approach. Each evaluation gets a unique UUID
   * token, ensuring that concurrent evaluations cannot interfere with each other.
   *
   * Results are automatically cleaned up after retrieval via remove() in
   * evaluateExpression(). This prevents memory leaks while maintaining full
   * thread safety.
   *
   * Note: Null values are represented by NULL_SENTINEL since ConcurrentHashMap
   * does not support null values.
   */
  public static final ConcurrentHashMap<String, Object> inspectionResults = new ConcurrentHashMap<>();

  private Map<String, Object> inspectObject(Object obj, String expression, boolean includePrivate, int maxDepth) {
    Map<String, Object> result = new HashMap<>();
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
