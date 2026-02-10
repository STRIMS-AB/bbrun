package org.bbrun.ast;

public record PrintNode(ExpressionNode message, int line) implements StatementNode {
}
