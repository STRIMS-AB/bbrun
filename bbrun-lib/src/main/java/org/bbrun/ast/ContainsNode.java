package org.bbrun.ast;

public record ContainsNode(ExpressionNode container, ExpressionNode item, boolean negated, ContainsMode mode)
        implements ExpressionNode {
    public enum ContainsMode {
        ITEM, ALL, ANY
    }
}
