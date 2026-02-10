package org.bbrun.ast;

public record ApiKeyAuthNode(String header, ExpressionNode value, int line) implements AuthNode {
}
