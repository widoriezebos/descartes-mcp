package com.bitsapplied.descartes.debugger.evaluation;

import java.util.List;
import java.util.Map;

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

import jdk.jshell.JShell;
import jdk.jshell.SnippetEvent;

/**
 * Evaluates complex Java expressions using JShell.
 *
 * <p>
 * JShell provides a full REPL environment that can handle:
 * <ul>
 * <li>Lambda expressions</li>
 * <li>Method references</li>
 * <li>Complex type inference</li>
 * <li>Multi-statement expressions</li>
 * </ul>
 *
 * <p>
 * Note: JShell is heavier than Janino, so it should be used as a fallback.
 */
public class JShellEvaluator {
  private static final Logger logger = LoggerFactory.getLogger(JShellEvaluator.class);

  private JShell jshell;

  /**
   * Creates a JShell evaluator.
   */
  public JShellEvaluator() {
    initializeJShell();
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
      // Reset JShell state for clean evaluation
      resetJShell();

      // Inject frame variables into JShell
      Map<LocalVariable, Value> frameValues = frame.getValues(frame.visibleVariables());

      for (Map.Entry<LocalVariable, Value> entry : frameValues.entrySet()) {
        LocalVariable var = entry.getKey();
        Value value = entry.getValue();

        String varDeclaration = buildVariableDeclaration(var, value);
        if (varDeclaration != null) {
          jshell.eval(varDeclaration);
        }
      }

      // Evaluate the expression
      List<SnippetEvent> events = jshell.eval(expression);

      if (events.isEmpty()) {
        throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED, "JShell evaluation returned no results");
      }

      SnippetEvent event = events.get(0);

      if (event.exception() != null) {
        throw new DebuggerException(DebuggerErrorCode.EVALUATION_EXECUTION_FAILED,
            "JShell execution exception: " + event.exception());
      }

      String result = event.value();
      if (result == null) {
        result = "null";
      }

      return result;

    } catch (DebuggerException e) {
      throw e;
    } catch (Exception e) {
      logger.debug("JShell evaluation failed for '{}': {}", expression, e.getMessage());
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED, "JShell evaluation failed: " + e.getMessage(),
          e);
    }
  }

  /**
   * Closes the JShell instance and releases resources.
   */
  public void close() {
    if (jshell != null) {
      jshell.close();
      jshell = null;
    }
  }

  // ========== Internal Methods ==========

  /**
   * Initializes a new JShell instance.
   */
  private void initializeJShell() {
    jshell = JShell.builder().build();
  }

  /**
   * Resets JShell to a clean state.
   */
  private void resetJShell() {
    close();
    initializeJShell();
  }

  /**
   * Builds a variable declaration for JShell.
   */
  private String buildVariableDeclaration(LocalVariable var, Value value) {
    String typeName = var.typeName();
    String varName = var.name();

    if (value == null) {
      return String.format("%s %s = null;", typeName, varName);
    }

    if (value instanceof IntegerValue intVal) {
      return String.format("int %s = %d;", varName, intVal.value());
    }

    if (value instanceof LongValue longVal) {
      return String.format("long %s = %dL;", varName, longVal.value());
    }

    if (value instanceof ShortValue shortVal) {
      return String.format("short %s = (short)%d;", varName, shortVal.value());
    }

    if (value instanceof ByteValue byteVal) {
      return String.format("byte %s = (byte)%d;", varName, byteVal.value());
    }

    if (value instanceof CharValue charVal) {
      return String.format("char %s = '%c';", varName, charVal.value());
    }

    if (value instanceof FloatValue floatVal) {
      return String.format("float %s = %ff;", varName, floatVal.value());
    }

    if (value instanceof DoubleValue doubleVal) {
      return String.format("double %s = %f;", varName, doubleVal.value());
    }

    if (value instanceof BooleanValue boolVal) {
      return String.format("boolean %s = %b;", varName, boolVal.value());
    }

    if (value instanceof StringReference stringRef) {
      String escapedString = stringRef.value().replace("\\", "\\\\").replace("\"", "\\\"");
      return String.format("String %s = \"%s\";", varName, escapedString);
    }

    // For object references, we can't easily inject them into JShell
    // This is a known limitation
    logger.debug("Cannot inject object variable '{}' of type '{}' into JShell", varName, typeName);
    return null;
  }
}
