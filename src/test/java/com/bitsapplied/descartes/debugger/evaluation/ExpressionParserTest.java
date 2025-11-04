package com.bitsapplied.descartes.debugger.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.evaluation.ExpressionParser.ExpressionType;

/**
 * Tests for ExpressionParser.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Variable extraction</li>
 * <li>Expression classification</li>
 * <li>Syntax validation</li>
 * <li>Complexity detection</li>
 * <li>Method call detection</li>
 * <li>Field access detection</li>
 * </ul>
 */
public class ExpressionParserTest {
  private static final Logger logger = LoggerFactory.getLogger(ExpressionParserTest.class);

  private ExpressionParser parser;

  @BeforeEach
  public void setUp() {
    parser = new ExpressionParser();
  }

  /**
   * Tests extracting variables from simple expression.
   */
  @Test
  public void testExtractVariablesSimple() {
    logger.info("Testing extract variables simple...");

    Set<String> variables = parser.extractVariables("x + y");

    assertNotNull(variables);
    assertEquals(2, variables.size());
    assertTrue(variables.contains("x"));
    assertTrue(variables.contains("y"));

    logger.info("Extract variables simple test passed");
  }

  /**
   * Tests extracting variables filters out Java keywords.
   */
  @Test
  public void testExtractVariablesFiltersKeywords() {
    logger.info("Testing extract variables filters keywords...");

    Set<String> variables = parser.extractVariables("int x = 10; return x + 5;");

    // Should not include 'int' or 'return' (keywords)
    assertFalse(variables.contains("int"));
    assertFalse(variables.contains("return"));

    // Should include 'x' (variable)
    assertTrue(variables.contains("x"));

    logger.info("Extract variables filters keywords test passed");
  }

  /**
   * Tests extracting variables from complex expression.
   */
  @Test
  public void testExtractVariablesComplex() {
    logger.info("Testing extract variables complex...");

    Set<String> variables = parser.extractVariables("person.getName().equals(\"John\") && age > 18");

    assertTrue(variables.contains("person"));
    assertTrue(variables.contains("getName"));
    assertTrue(variables.contains("equals"));
    assertTrue(variables.contains("age"));

    logger.info("Extract variables complex test passed");
  }

  /**
   * Tests isSimpleExpression for simple cases.
   */
  @Test
  public void testIsSimpleExpressionTrue() {
    logger.info("Testing is simple expression true...");

    assertTrue(parser.isSimpleExpression("x + y"));
    assertTrue(parser.isSimpleExpression("person.age > 18"));
    assertTrue(parser.isSimpleExpression("count * 2"));
    assertTrue(parser.isSimpleExpression("name.equals(\"test\")"));

    logger.info("Is simple expression true test passed");
  }

  /**
   * Tests isSimpleExpression for complex cases.
   */
  @Test
  public void testIsSimpleExpressionFalse() {
    logger.info("Testing is simple expression false...");

    assertFalse(parser.isSimpleExpression("import java.util.List"));
    assertFalse(parser.isSimpleExpression("class Foo {}"));
    assertFalse(parser.isSimpleExpression("interface Bar {}"));
    assertFalse(parser.isSimpleExpression("x -> x * 2")); // Lambda
    assertFalse(parser.isSimpleExpression("String::length")); // Method reference

    logger.info("Is simple expression false test passed");
  }

  /**
   * Tests isMethodCall detection.
   */
  @Test
  public void testIsMethodCall() {
    logger.info("Testing is method call...");

    assertTrue(parser.isMethodCall("getName()"));
    assertTrue(parser.isMethodCall("person.getName()"));
    assertTrue(parser.isMethodCall("calculate(x, y)"));
    assertTrue(parser.isMethodCall("Math.max(a, b)"));

    assertFalse(parser.isMethodCall("x + y"));
    assertFalse(parser.isMethodCall("person.name"));

    logger.info("Is method call test passed");
  }

  /**
   * Tests isFieldAccess detection.
   */
  @Test
  public void testIsFieldAccess() {
    logger.info("Testing is field access...");

    assertTrue(parser.isFieldAccess("x"));
    assertTrue(parser.isFieldAccess("person.name"));
    assertTrue(parser.isFieldAccess("obj.field.subfield"));

    assertFalse(parser.isFieldAccess("getName()"));
    assertFalse(parser.isFieldAccess("x + y"));
    assertFalse(parser.isFieldAccess("person.getName()"));

    logger.info("Is field access test passed");
  }

  /**
   * Tests classifyExpression for literals.
   */
  @Test
  public void testClassifyExpressionLiteral() {
    logger.info("Testing classify expression literal...");

    assertEquals(ExpressionType.LITERAL, parser.classifyExpression("42"));
    assertEquals(ExpressionType.LITERAL, parser.classifyExpression("\"hello\""));
    assertEquals(ExpressionType.LITERAL, parser.classifyExpression("'c'"));
    assertEquals(ExpressionType.LITERAL, parser.classifyExpression("true"));
    assertEquals(ExpressionType.LITERAL, parser.classifyExpression("false"));
    assertEquals(ExpressionType.LITERAL, parser.classifyExpression("null"));

    logger.info("Classify expression literal test passed");
  }

  /**
   * Tests classifyExpression for field access.
   */
  @Test
  public void testClassifyExpressionFieldAccess() {
    logger.info("Testing classify expression field access...");

    assertEquals(ExpressionType.FIELD_ACCESS, parser.classifyExpression("x"));
    assertEquals(ExpressionType.FIELD_ACCESS, parser.classifyExpression("person.name"));
    assertEquals(ExpressionType.FIELD_ACCESS, parser.classifyExpression("obj.field.subfield"));

    logger.info("Classify expression field access test passed");
  }

  /**
   * Tests classifyExpression for method calls.
   */
  @Test
  public void testClassifyExpressionMethodCall() {
    logger.info("Testing classify expression method call...");

    assertEquals(ExpressionType.METHOD_CALL, parser.classifyExpression("getName()"));
    assertEquals(ExpressionType.METHOD_CALL, parser.classifyExpression("person.getName()"));
    assertEquals(ExpressionType.METHOD_CALL, parser.classifyExpression("calculate(x, y)"));

    logger.info("Classify expression method call test passed");
  }

  /**
   * Tests classifyExpression for simple expressions.
   */
  @Test
  public void testClassifyExpressionSimple() {
    logger.info("Testing classify expression simple...");

    assertEquals(ExpressionType.SIMPLE, parser.classifyExpression("x + y"));
    assertEquals(ExpressionType.SIMPLE, parser.classifyExpression("count * 2"));
    assertEquals(ExpressionType.SIMPLE, parser.classifyExpression("age > 18"));

    logger.info("Classify expression simple test passed");
  }

  /**
   * Tests classifyExpression for complex expressions.
   */
  @Test
  public void testClassifyExpressionComplex() {
    logger.info("Testing classify expression complex...");

    assertEquals(ExpressionType.COMPLEX, parser.classifyExpression("import java.util.List"));
    assertEquals(ExpressionType.COMPLEX, parser.classifyExpression("x -> x * 2"));
    assertEquals(ExpressionType.COMPLEX, parser.classifyExpression("String::length"));

    logger.info("Classify expression complex test passed");
  }

  /**
   * Tests classifyExpression for invalid expressions.
   */
  @Test
  public void testClassifyExpressionInvalid() {
    logger.info("Testing classify expression invalid...");

    assertEquals(ExpressionType.INVALID, parser.classifyExpression(""));
    assertEquals(ExpressionType.INVALID, parser.classifyExpression("   "));

    logger.info("Classify expression invalid test passed");
  }

  /**
   * Tests validateSyntax for valid expressions.
   */
  @Test
  public void testValidateSyntaxValid() {
    logger.info("Testing validate syntax valid...");

    assertTrue(parser.validateSyntax("x + y"));
    assertTrue(parser.validateSyntax("(a + b) * c"));
    assertTrue(parser.validateSyntax("arr[0]"));
    assertTrue(parser.validateSyntax("map.get(\"key\")"));
    assertTrue(parser.validateSyntax("\"hello world\""));
    assertTrue(parser.validateSyntax("{a: 1, b: 2}"));

    logger.info("Validate syntax valid test passed");
  }

  /**
   * Tests validateSyntax for invalid expressions.
   */
  @Test
  public void testValidateSyntaxInvalid() {
    logger.info("Testing validate syntax invalid...");

    assertFalse(parser.validateSyntax("(x + y")); // Unbalanced paren
    assertFalse(parser.validateSyntax("x + y)")); // Unbalanced paren
    assertFalse(parser.validateSyntax("arr[0")); // Unbalanced bracket
    assertFalse(parser.validateSyntax("{a: 1")); // Unbalanced brace
    assertFalse(parser.validateSyntax("\"unclosed string")); // Unclosed quote

    logger.info("Validate syntax invalid test passed");
  }

  /**
   * Tests validateSyntax handles nested structures.
   */
  @Test
  public void testValidateSyntaxNested() {
    logger.info("Testing validate syntax nested...");

    assertTrue(parser.validateSyntax("((a + b) * (c - d))"));
    assertTrue(parser.validateSyntax("arr[arr[0]]"));
    assertTrue(parser.validateSyntax("{a: {b: {c: 1}}}"));

    assertFalse(parser.validateSyntax("((a + b) * (c - d)"));
    assertFalse(parser.validateSyntax("arr[arr[0]"));

    logger.info("Validate syntax nested test passed");
  }

  /**
   * Tests validateSyntax handles escaped characters in strings.
   */
  @Test
  public void testValidateSyntaxEscapedChars() {
    logger.info("Testing validate syntax escaped chars...");

    assertTrue(parser.validateSyntax("\"hello \\\" world\""));
    assertTrue(parser.validateSyntax("\"path\\\\to\\\\file\""));

    logger.info("Validate syntax escaped chars test passed");
  }

  /**
   * Tests extracting no variables from literals.
   */
  @Test
  public void testExtractVariablesFromLiterals() {
    logger.info("Testing extract variables from literals...");

    Set<String> variables = parser.extractVariables("42");
    assertTrue(variables.isEmpty());

    variables = parser.extractVariables("\"hello\"");
    assertTrue(variables.isEmpty() || variables.stream().allMatch(v -> v.equals("hello")));

    logger.info("Extract variables from literals test passed");
  }

  /**
   * Tests variable extraction preserves order (LinkedHashSet).
   */
  @Test
  public void testExtractVariablesPreservesOrder() {
    logger.info("Testing extract variables preserves order...");

    Set<String> variables = parser.extractVariables("first.second.third");

    // LinkedHashSet should preserve insertion order
    String[] expected = { "first", "second", "third" };
    String[] actual = variables.toArray(new String[0]);

    assertEquals(expected.length, actual.length);
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], actual[i]);
    }

    logger.info("Extract variables preserves order test passed");
  }
}
