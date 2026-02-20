package com.bitsapplied.descartes.debugger.evaluation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.codehaus.janino.Java;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;

/**
 * Tests that Janino parses various expression patterns correctly. No JDI
 * runtime needed — this validates that the parser produces AST nodes for all
 * supported expression types.
 */
class JdiRemoteEvaluatorParserTest {

  @Test
  void parsesIntegerLiterals() {
    for (String expr : new String[] { "1", "42", "0", "1000000", "0xFF", "0b1010" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesLongLiterals() {
    for (String expr : new String[] { "1L", "42L", "100000000000L" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesFloatingPointLiterals() {
    for (String expr : new String[] { "1.0", "3.14", "2.718d", "1.5f", "0.0f" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesBooleanLiterals() {
    assertNotNull(JdiRemoteEvaluator.parseExpression("true"));
    assertNotNull(JdiRemoteEvaluator.parseExpression("false"));
  }

  @Test
  void parsesCharLiterals() {
    for (String expr : new String[] { "'a'", "'\\n'", "'\\t'", "'\\\\'" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesStringLiterals() {
    for (String expr : new String[] { "\"hello\"", "\"\"", "\"hello world\"", "\"line\\nbreak\"" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesNullLiteral() {
    assertNotNull(JdiRemoteEvaluator.parseExpression("null"));
  }

  @Test
  void parsesArithmeticExpressions() {
    for (String expr : new String[] { "1 + 1", "a + b", "x * y - z", "10 / 2", "7 % 3" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesComparisonExpressions() {
    for (String expr : new String[] { "a > 0", "x <= y", "a == b", "a != b", "x >= 10", "a < b" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesBooleanExpressions() {
    for (String expr : new String[] { "a && b", "x || y", "!flag", "a && b || c" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesTernaryExpressions() {
    for (String expr : new String[] { "a ? b : c", "x > 0 ? x : -x", "flag ? \"yes\" : \"no\"" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesThisExpressions() {
    for (String expr : new String[] { "this", "this.field", "this.getX()" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesVariableAndFieldAccess() {
    for (String expr : new String[] { "x", "myVar", "camelCase", "a.b", "a.b.c" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesMethodInvocations() {
    for (String expr : new String[] { "foo()", "bar(1)", "baz(a, b)", "obj.method()", "obj.method(1, \"two\")" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesArrayAccess() {
    for (String expr : new String[] { "arr[0]", "arr[i]", "matrix[i][j]", "list.get(0)" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesCastExpressions() {
    for (String expr : new String[] { "(int) x", "(String) obj", "(double) i" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesInstanceofExpressions() {
    for (String expr : new String[] { "obj instanceof String", "x instanceof Number" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesNewExpressions() {
    for (String expr : new String[] { "new Object()", "new String(\"hello\")", "new ArrayList()" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesParenthesizedExpressions() {
    for (String expr : new String[] { "(a + b)", "((x))", "(a > 0) && (b < 10)" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesUnaryExpressions() {
    for (String expr : new String[] { "-x", "+y", "~bits", "!flag" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesStringConcatenation() {
    for (String expr : new String[] { "\"hello\" + \" world\"", "\"count: \" + n", "a + \" items\"" }) {
      assertNotNull(JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }

  @Test
  void parsesComplexNestedExpression() {
    String expr = "a > 0 ? list.size() + 1 : (b * 2) - c";
    assertNotNull(JdiRemoteEvaluator.parseExpression(expr));
  }

  @Test
  void parsesChainedMethodCalls() {
    String expr = "list.get(0).toString().length()";
    assertNotNull(JdiRemoteEvaluator.parseExpression(expr));
  }

  @Test
  void rejectsInvalidExpression() {
    assertThrows(DebuggerException.class, () -> JdiRemoteEvaluator.parseExpression("if (true) {}"));
  }

  @Test
  void rejectsEmptyExpression() {
    assertThrows(DebuggerException.class, () -> JdiRemoteEvaluator.parseExpression(""));
  }

  @Test
  void parsesBitwiseExpressions() {
    for (String expr : new String[] { "a & b", "x | y", "a ^ b", "a << 2", "a >> 1", "a >>> 1" }) {
      assertDoesNotThrow(() -> JdiRemoteEvaluator.parseExpression(expr), "Failed to parse: " + expr);
    }
  }
}
