package org.bbrun.ast;

public record BearerAuthNode(ExpressionNode token, int line) implements AuthNode {
}
