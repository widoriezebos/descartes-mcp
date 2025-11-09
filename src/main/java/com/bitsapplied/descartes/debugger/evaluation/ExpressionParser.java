package com.bitsapplied.descartes.debugger.evaluation;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and analyzes Java expressions for debugger evaluation.
 *
 * <p>
 * Capabilities:
 * <ul>
 * <li>Extract variable references from expressions</li>
 * <li>Detect expression complexity</li>
 * <li>Validate basic expression syntax</li>
 * <li>Classify expression types</li>
 * </ul>
 */
public class ExpressionParser {

  private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b");
  private static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
      "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
      "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
      "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
      "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true",
      "false", "null");

  /**
   * Extracts variable references from an expression.
   *
   * @param expression the expression to parse
   * @return set of variable names referenced
   */
  public Set<String> extractVariables(String expression) {
    Set<String> variables = new LinkedHashSet<>();

    Matcher matcher = IDENTIFIER_PATTERN.matcher(expression);
    while (matcher.find()) {
      String identifier = matcher.group(1);
      // Filter out Java keywords
      if (!JAVA_KEYWORDS.contains(identifier)) {
        variables.add(identifier);
      }
    }

    return variables;
  }

  /**
   * Determines if an expression is simple (can be evaluated by Janino).
   *
   * @param expression the expression
   * @return true if simple
   */
  public boolean isSimpleExpression(String expression) {
    // Check for complex constructs that require JShell
    if (expression.contains("import "))
      return false;
    if (expression.contains("class "))
      return false;
    if (expression.contains("interface "))
      return false;
    if (expression.contains("lambda"))
      return false;
    if (expression.contains("->"))
      return false; // Lambda operator
    if (expression.contains("::"))
      return false; // Method reference

    return true;
  }

  /**
   * Checks if an expression is a method call.
   *
   * @param expression the expression
   * @return true if it contains method call syntax
   */
  public boolean isMethodCall(String expression) {
    return expression.matches(".*\\w+\\s*\\(.*\\).*");
  }

  /**
   * Checks if an expression is a field access.
   *
   * @param expression the expression
   * @return true if it's simple field access
   */
  public boolean isFieldAccess(String expression) {
    return expression.matches("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$");
  }

  /**
   * Classifies the expression type.
   *
   * @param expression the expression
   * @return expression type
   */
  public ExpressionType classifyExpression(String expression) {
    String trimmed = expression.trim();

    if (trimmed.isEmpty()) {
      return ExpressionType.INVALID;
    }

    if (trimmed.matches("^\\d+$")) {
      return ExpressionType.LITERAL;
    }

    if (trimmed.matches("^\".*\"$") || trimmed.matches("^'.*'$")) {
      return ExpressionType.LITERAL;
    }

    if (trimmed.equals("true") || trimmed.equals("false") || trimmed.equals("null")) {
      return ExpressionType.LITERAL;
    }

    if (isFieldAccess(trimmed)) {
      return ExpressionType.FIELD_ACCESS;
    }

    if (isMethodCall(trimmed)) {
      return ExpressionType.METHOD_CALL;
    }

    if (!isSimpleExpression(trimmed)) {
      return ExpressionType.COMPLEX;
    }

    return ExpressionType.SIMPLE;
  }

  /**
   * Validates basic expression syntax.
   *
   * @param expression the expression
   * @return true if syntax appears valid
   */
  public boolean validateSyntax(String expression) {
    // Basic checks for balanced parentheses, brackets, quotes
    int parenCount = 0;
    int bracketCount = 0;
    int braceCount = 0;
    boolean inString = false;
    boolean escaped = false;

    for (char c : expression.toCharArray()) {
      if (escaped) {
        escaped = false;
        continue;
      }

      if (c == '\\') {
        escaped = true;
        continue;
      }

      if (c == '"') {
        inString = !inString;
        continue;
      }

      if (inString)
        continue;

      switch (c) {
      case '(' -> parenCount++;
      case ')' -> parenCount--;
      case '[' -> bracketCount++;
      case ']' -> bracketCount--;
      case '{' -> braceCount++;
      case '}' -> braceCount--;
      }

      // Early exit on negative counts
      if (parenCount < 0 || bracketCount < 0 || braceCount < 0) {
        return false;
      }
    }

    return parenCount == 0 && bracketCount == 0 && braceCount == 0 && !inString;
  }

  /**
   * Expression type classification.
   */
  public enum ExpressionType {
    LITERAL, // Constant value
    FIELD_ACCESS, // Simple field access (a.b.c)
    METHOD_CALL, // Method invocation
    SIMPLE, // Simple expression (Janino can handle)
    COMPLEX, // Complex expression (needs JShell)
    INVALID // Invalid syntax
  }
}
