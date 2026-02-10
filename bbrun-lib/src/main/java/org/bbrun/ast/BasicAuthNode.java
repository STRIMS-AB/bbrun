package org.bbrun.ast;

public record BasicAuthNode(ExpressionNode username, ExpressionNode password, int line) implements AuthNode {
}
