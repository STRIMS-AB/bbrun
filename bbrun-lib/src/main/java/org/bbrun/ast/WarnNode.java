package org.bbrun.ast;

public record WarnNode(ExpressionNode condition, String message, int line) implements StatementNode {
}
