package org.bbrun.ast;

public record UnaryOpNode(String operator, ExpressionNode operand) implements ExpressionNode {
}
