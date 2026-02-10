package org.bbrun.ast;

public record AssertNode(ExpressionNode condition, String message, int line) implements StatementNode {
}
