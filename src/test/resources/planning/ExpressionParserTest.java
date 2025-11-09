package com.bitsapplied.descartes.debugger.expression.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExpressionParserTest {

  @Test
  void parsesSimpleArithmetic() {
    ExpressionNode node = ExpressionParser.parse("a + b * 3");
    assertThat(node).isInstanceOf(BinaryExpressionNode.class);
    BinaryExpressionNode sum = (BinaryExpressionNode) node;
    assertThat(sum.operator()).isEqualTo(BinaryExpressionNode.Operator.ADD);
    assertThat(sum.left()).isInstanceOf(IdentifierExpressionNode.class);
    BinaryExpressionNode product = (BinaryExpressionNode) sum.right();
    assertThat(product.operator()).isEqualTo(BinaryExpressionNode.Operator.MULTIPLY);
  }

  @Test
  void parsesMethodCallChain() {
    ExpressionNode node = ExpressionParser.parse("order.getCustomer().getName()");
    assertThat(node).isInstanceOf(MethodInvocationExpressionNode.class);
    MethodInvocationExpressionNode call = (MethodInvocationExpressionNode) node;
    assertThat(call.methodName()).isEqualTo("getName");
    assertThat(call.arguments()).isEmpty();
    assertThat(call.target()).isInstanceOf(MethodInvocationExpressionNode.class);
  }

  @Test
  void parsesTernaryExpression() {
    ExpressionNode node = ExpressionParser.parse("flag ? foo : bar");
    assertThat(node).isInstanceOf(TernaryExpressionNode.class);
  }

  @Test
  void parsesArrayAccess() {
    ExpressionNode node = ExpressionParser.parse("items[0].name");
    assertThat(node).isInstanceOf(MemberAccessExpressionNode.class);
    MemberAccessExpressionNode member = (MemberAccessExpressionNode) node;
    assertThat(member.member()).isEqualTo("name");
    assertThat(member.target()).isInstanceOf(ArrayAccessExpressionNode.class);
  }

  @Test
  void respectsParentheses() {
    ExpressionNode node = ExpressionParser.parse("(a + b) * c");
    BinaryExpressionNode product = (BinaryExpressionNode) node;
    assertThat(product.operator()).isEqualTo(BinaryExpressionNode.Operator.MULTIPLY);
    assertThat(product.left()).isInstanceOf(GroupingExpressionNode.class);
  }

  @Test
  void throwsOnUnexpectedToken() {
    assertThatThrownBy(() -> ExpressionParser.parse("@foo"))
        .isInstanceOf(ExpressionParseException.class)
        .hasMessageContaining("Unexpected character");
  }
}
