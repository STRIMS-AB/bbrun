package org.bbrun.ast;

public record ExpressionStatementNode(ExpressionNode expression, int line) implements StatementNode {
}
