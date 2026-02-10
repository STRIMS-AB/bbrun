package org.bbrun.ast;

public record IndexAccessNode(ExpressionNode object, ExpressionNode index) implements ExpressionNode {
}
