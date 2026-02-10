package org.bbrun.ast;

public record MatchesSchemaNode(ExpressionNode expression, ExpressionNode schema) implements ExpressionNode {
}
