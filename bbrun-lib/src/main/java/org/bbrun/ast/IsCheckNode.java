package org.bbrun.ast;

public record IsCheckNode(ExpressionNode expression, String typeOrFormat, boolean isRegex) implements ExpressionNode {
}
