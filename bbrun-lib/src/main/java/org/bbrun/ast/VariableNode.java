package org.bbrun.ast;

/** Variable declaration statement. */
public record VariableNode(String name, ExpressionNode value, int line) implements StatementNode {
}
