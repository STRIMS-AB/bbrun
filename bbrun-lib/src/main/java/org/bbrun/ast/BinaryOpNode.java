package org.bbrun.ast;

public record BinaryOpNode(ExpressionNode left, String operator, ExpressionNode right) implements ExpressionNode {
}
