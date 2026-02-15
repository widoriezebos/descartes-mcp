package com.bitsapplied.descartes.debugger.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import jdk.jshell.Snippet;
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
    DebuggerException initialFailure;
    try {
      return evaluateWithMode(expression, frame, InjectionMode.PRIMARY);
    } catch (DebuggerException e) {
      initialFailure = e;
    }

    try {
      String retried = evaluateWithMode(expression, frame, InjectionMode.SANITIZED);
      logger.debug("JShell evaluation succeeded after sanitized retry for expression '{}'", expression);
      return retried;
    } catch (DebuggerException sanitizedFailure) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_EXECUTION_FAILED,
          "JShell evaluation failed after sanitized retry. Initial failure: " + initialFailure.getMessage()
              + "; sanitized retry failure: " + sanitizedFailure.getMessage(),
          sanitizedFailure);

    } catch (Exception e) {
      logger.debug("JShell sanitized retry failed for '{}': {}", expression, e.getMessage());
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED, "JShell evaluation failed: " + e.getMessage(),
          e);
    }
  }

  private String evaluateWithMode(String expression, StackFrame frame, InjectionMode mode) {
    try {
      resetJShell();
      Set<String> unresolvedFrameVariables = new LinkedHashSet<>();

      Map<LocalVariable, Value> frameValues = frame.getValues(frame.visibleVariables());
      Map<String, String> aliasMap =
          mode == InjectionMode.SANITIZED ? buildAliasMap(frameValues.keySet(), expression) : Map.of();
      Set<String> referencedVariables = mode == InjectionMode.SANITIZED ? aliasMap.keySet() : Set.of();

      for (Map.Entry<LocalVariable, Value> entry : frameValues.entrySet()) {
        LocalVariable var = entry.getKey();
        Value value = entry.getValue();
        String originalName = var.name();

        if (mode == InjectionMode.SANITIZED && !referencedVariables.contains(originalName)) {
          continue;
        }

        String bindingName = mode == InjectionMode.SANITIZED ? aliasMap.get(originalName) : originalName;
        String varDeclaration = buildVariableDeclaration(var, value, bindingName, mode);
        if (varDeclaration == null) {
          unresolvedFrameVariables.add(originalName);
          continue;
        }

        if (!injectVariable(varDeclaration, originalName, unresolvedFrameVariables)) {
          continue;
        }
      }

      String expressionToEvaluate =
          mode == InjectionMode.SANITIZED ? rewriteExpressionIdentifiers(expression, aliasMap) : expression;
      List<SnippetEvent> events = jshell.eval(expressionToEvaluate);
      validateEvents("expression evaluation", events, unresolvedFrameVariables);
      return extractResultValue(events);

    } catch (Exception e) {
      if (e instanceof DebuggerException debuggerException) {
        throw debuggerException;
      }
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
  private String buildVariableDeclaration(LocalVariable var, Value value, String bindingName, InjectionMode mode) {
    String typeName = var.typeName();

    if (value == null) {
      if (mode == InjectionMode.SANITIZED) {
        return String.format("Object %s = null;", bindingName);
      }
      return String.format("%s %s = null;", typeName, bindingName);
    }

    if (value instanceof IntegerValue intVal) {
      return String.format("int %s = %d;", bindingName, intVal.value());
    }

    if (value instanceof LongValue longVal) {
      return String.format("long %s = %dL;", bindingName, longVal.value());
    }

    if (value instanceof ShortValue shortVal) {
      return String.format("short %s = (short)%d;", bindingName, shortVal.value());
    }

    if (value instanceof ByteValue byteVal) {
      return String.format("byte %s = (byte)%d;", bindingName, byteVal.value());
    }

    if (value instanceof CharValue charVal) {
      return String.format("char %s = '%s';", bindingName, escapeCharLiteral(charVal.value()));
    }

    if (value instanceof FloatValue floatVal) {
      return String.format("float %s = %ff;", bindingName, floatVal.value());
    }

    if (value instanceof DoubleValue doubleVal) {
      return String.format("double %s = %f;", bindingName, doubleVal.value());
    }

    if (value instanceof BooleanValue boolVal) {
      return String.format("boolean %s = %b;", bindingName, boolVal.value());
    }

    if (value instanceof StringReference stringRef) {
      String escapedString = stringRef.value().replace("\\", "\\\\").replace("\"", "\\\"");
      return String.format("String %s = \"%s\";", bindingName, escapedString);
    }

    // For object references, we can't easily inject them into JShell
    // This is a known limitation
    logger.debug("Cannot inject object variable '{}' of type '{}' into JShell", var.name(), typeName);
    return null;
  }

  private boolean injectVariable(String declaration, String originalName, Set<String> unresolvedFrameVariables) {
    List<SnippetEvent> declarationEvents = jshell.eval(declaration);
    if (declarationEvents.isEmpty()) {
      unresolvedFrameVariables.add(originalName);
      return false;
    }

    for (SnippetEvent event : declarationEvents) {
      if (event.exception() != null) {
        unresolvedFrameVariables.add(originalName);
        return false;
      }
      if (event.status() != Snippet.Status.VALID) {
        unresolvedFrameVariables.add(originalName);
        return false;
      }
    }
    return true;
  }

  private void validateEvents(String operation, List<SnippetEvent> events, Set<String> unresolvedFrameVariables) {
    if (events.isEmpty()) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED, "JShell " + operation + " returned no results");
    }

    for (SnippetEvent event : events) {
      if (event.exception() != null) {
        throw new DebuggerException(DebuggerErrorCode.EVALUATION_EXECUTION_FAILED,
            "JShell " + operation + " threw: " + event.exception());
      }

      if (event.status() != Snippet.Status.VALID) {
        throw new DebuggerException(DebuggerErrorCode.EVALUATION_EXECUTION_FAILED,
            buildStatusFailureMessage(operation, event, unresolvedFrameVariables));
      }
    }
  }

  private String buildStatusFailureMessage(String operation, SnippetEvent event, Set<String> unresolvedFrameVariables) {
    StringBuilder message = new StringBuilder("JShell ").append(operation).append(" failed with status ")
        .append(event.status());

    String diagnostics = diagnosticsFor(event.snippet());
    if (!diagnostics.isBlank()) {
      message.append(": ").append(diagnostics);
    }

    if (!unresolvedFrameVariables.isEmpty()) {
      message.append(". Frame variables unavailable in JShell context: ")
          .append(String.join(", ", unresolvedFrameVariables));
    }
    return message.toString();
  }

  private String diagnosticsFor(Snippet snippet) {
    if (snippet == null) {
      return "";
    }
    List<String> diagnostics = jshell.diagnostics(snippet).map(diag -> diag.getMessage(Locale.ROOT))
        .map(String::trim).filter(msg -> !msg.isEmpty()).collect(Collectors.toCollection(ArrayList::new));
    return String.join(" | ", diagnostics);
  }

  private String extractResultValue(List<SnippetEvent> events) {
    for (int i = events.size() - 1; i >= 0; i--) {
      String value = events.get(i).value();
      if (value != null) {
        return value;
      }
    }
    return "null";
  }

  private Map<String, String> buildAliasMap(Set<LocalVariable> variables, String expression) {
    Set<String> referencedIdentifiers = extractIdentifierTokens(expression);
    Map<String, String> aliases = new LinkedHashMap<>();
    int index = 0;
    for (LocalVariable variable : variables) {
      String variableName = variable.name();
      if (!referencedIdentifiers.contains(variableName)) {
        continue;
      }
      aliases.put(variableName, "v" + index++);
    }
    return aliases;
  }

  private Set<String> extractIdentifierTokens(String expression) {
    Set<String> tokens = new HashSet<>();
    if (expression == null || expression.isBlank()) {
      return tokens;
    }

    boolean inString = false;
    boolean inChar = false;
    boolean escaping = false;

    for (int i = 0; i < expression.length();) {
      char ch = expression.charAt(i);

      if (inString || inChar) {
        if (escaping) {
          escaping = false;
        } else if (ch == '\\') {
          escaping = true;
        } else if ((inString && ch == '"') || (inChar && ch == '\'')) {
          inString = false;
          inChar = false;
        }
        i++;
        continue;
      }

      if (ch == '"') {
        inString = true;
        i++;
        continue;
      }
      if (ch == '\'') {
        inChar = true;
        i++;
        continue;
      }

      if (Character.isJavaIdentifierStart(ch)) {
        int end = i + 1;
        while (end < expression.length() && Character.isJavaIdentifierPart(expression.charAt(end))) {
          end++;
        }
        tokens.add(expression.substring(i, end));
        i = end;
        continue;
      }
      i++;
    }

    return tokens;
  }

  private String rewriteExpressionIdentifiers(String expression, Map<String, String> aliasMap) {
    if (aliasMap.isEmpty() || expression == null || expression.isBlank()) {
      return expression;
    }

    StringBuilder rewritten = new StringBuilder();
    boolean inString = false;
    boolean inChar = false;
    boolean escaping = false;

    for (int i = 0; i < expression.length();) {
      char ch = expression.charAt(i);

      if (inString || inChar) {
        rewritten.append(ch);
        if (escaping) {
          escaping = false;
        } else if (ch == '\\') {
          escaping = true;
        } else if ((inString && ch == '"') || (inChar && ch == '\'')) {
          inString = false;
          inChar = false;
        }
        i++;
        continue;
      }

      if (ch == '"') {
        inString = true;
        rewritten.append(ch);
        i++;
        continue;
      }
      if (ch == '\'') {
        inChar = true;
        rewritten.append(ch);
        i++;
        continue;
      }

      if (Character.isJavaIdentifierStart(ch)) {
        int end = i + 1;
        while (end < expression.length() && Character.isJavaIdentifierPart(expression.charAt(end))) {
          end++;
        }
        String token = expression.substring(i, end);
        rewritten.append(aliasMap.getOrDefault(token, token));
        i = end;
        continue;
      }

      rewritten.append(ch);
      i++;
    }

    return rewritten.toString();
  }

  private String escapeCharLiteral(char value) {
    return switch (value) {
    case '\'' -> "\\'";
    case '\\' -> "\\\\";
    case '\n' -> "\\n";
    case '\r' -> "\\r";
    case '\t' -> "\\t";
    case '\b' -> "\\b";
    case '\f' -> "\\f";
    default -> Character.toString(value);
    };
  }

  private enum InjectionMode {
    PRIMARY, SANITIZED
  }
}
