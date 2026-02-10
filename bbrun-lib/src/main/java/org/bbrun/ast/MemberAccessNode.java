package org.bbrun.ast;

public record MemberAccessNode(ExpressionNode object, String member) implements ExpressionNode {
}
