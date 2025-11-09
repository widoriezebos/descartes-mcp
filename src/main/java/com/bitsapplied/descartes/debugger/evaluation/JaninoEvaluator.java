package com.bitsapplied.descartes.debugger.evaluation;

import java.util.Map;

import org.codehaus.janino.ExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.LongValue;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

/**
 * Evaluates Java expressions using the Janino compiler.
 *
 * <p>
 * Janino provides fast, lightweight expression compilation without requiring a
 * full JDK. It's ideal for simple expressions and calculations.
 *
 * <p>
 * Limitations:
 * <ul>
 * <li>No lambda expressions</li>
 * <li>No method references</li>
 * <li>Limited type inference</li>
 * <li>Must declare all variable types explicitly</li>
 * </ul>
 */
public class JaninoEvaluator {
  private static final Logger logger = LoggerFactory.getLogger(JaninoEvaluator.class);

  private final ExpressionCache cache;

  /**
   * Creates a Janino evaluator with a cache.
   *
   * @param cache expression cache
   */
  public JaninoEvaluator(ExpressionCache cache) {
    this.cache = cache;
  }

  /**
   * Evaluates an expression in the context of a stack frame.
   *
   * @param expression the expression to evaluate
   * @param frame      the stack frame providing context
   * @return evaluation result as a string
   * @throws DebuggerException if evaluation fails
   */
  public String evaluate(String expression, StackFrame frame) {
    try {
      // Extract variables from frame
      Map<LocalVariable, Value> frameValues = frame.getValues(frame.visibleVariables());

      // Build Janino context
      String[] parameterNames = new String[frameValues.size()];
      Class<?>[] parameterTypes = new Class<?>[frameValues.size()];
      Object[] parameterValues = new Object[frameValues.size()];

      int index = 0;
      for (Map.Entry<LocalVariable, Value> entry : frameValues.entrySet()) {
        LocalVariable var = entry.getKey();
        Value value = entry.getValue();

        parameterNames[index] = var.name();
        parameterTypes[index] = mapJdiTypeToJavaClass(var.typeName());
        parameterValues[index] = convertJdiValueToJavaObject(value);

        index++;
      }

      // Check cache
      String cacheKey = buildCacheKey(expression, parameterNames, parameterTypes);
      ExpressionEvaluator evaluator = cache.get(cacheKey);

      if (evaluator == null) {
        // Compile expression
        evaluator = new ExpressionEvaluator();
        evaluator.setParameters(parameterNames, parameterTypes);
        evaluator.setExpressionType(Object.class);
        evaluator.cook(expression);

        // Cache for reuse
        cache.put(cacheKey, evaluator);
      }

      // Evaluate
      Object result = evaluator.evaluate(parameterValues);

      return formatResult(result);

    } catch (Exception e) {
      logger.debug("Janino evaluation failed for '{}': {}", expression, e.getMessage());
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_COMPILATION_FAILED,
          "Janino compilation failed: " + e.getMessage(), e);
    }
  }

  /**
   * Checks if Janino can handle this expression.
   *
   * @param expression the expression
   * @return true if Janino-compatible
   */
  public boolean canEvaluate(String expression) {
    ExpressionParser parser = new ExpressionParser();
    return parser.isSimpleExpression(expression);
  }

  // ========== Internal Methods ==========

  /**
   * Maps JDI type names to Java classes.
   */
  private Class<?> mapJdiTypeToJavaClass(String typeName) {
    return switch (typeName) {
    case "int" -> int.class;
    case "long" -> long.class;
    case "short" -> short.class;
    case "byte" -> byte.class;
    case "char" -> char.class;
    case "float" -> float.class;
    case "double" -> double.class;
    case "boolean" -> boolean.class;
    case "java.lang.String" -> String.class;
    default -> Object.class; // Generic object type
    };
  }

  /**
   * Converts JDI Value to Java object.
   */
  private Object convertJdiValueToJavaObject(Value value) {
    if (value == null) {
      return null;
    }

    if (value instanceof IntegerValue intVal) {
      return intVal.value();
    }

    if (value instanceof LongValue longVal) {
      return longVal.value();
    }

    if (value instanceof ShortValue shortVal) {
      return shortVal.value();
    }

    if (value instanceof ByteValue byteVal) {
      return byteVal.value();
    }

    if (value instanceof CharValue charVal) {
      return charVal.value();
    }

    if (value instanceof FloatValue floatVal) {
      return floatVal.value();
    }

    if (value instanceof DoubleValue doubleVal) {
      return doubleVal.value();
    }

    if (value instanceof BooleanValue boolVal) {
      return boolVal.value();
    }

    if (value instanceof StringReference stringRef) {
      return stringRef.value();
    }

    // For object references, we can't easily convert to Java objects
    // This is a limitation of Janino - it works best with primitives
    return value.toString();
  }

  /**
   * Formats evaluation result as a string.
   */
  private String formatResult(Object result) {
    if (result == null) {
      return "null";
    }

    if (result instanceof String str) {
      return "\"" + str + "\"";
    }

    return result.toString();
  }

  /**
   * Builds a cache key for an expression.
   */
  private String buildCacheKey(String expression, String[] names, Class<?>[] types) {
    StringBuilder key = new StringBuilder(expression);
    key.append("|");

    for (int i = 0; i < names.length; i++) {
      key.append(names[i]).append(":").append(types[i].getName()).append(",");
    }

    return key.toString();
  }
}
