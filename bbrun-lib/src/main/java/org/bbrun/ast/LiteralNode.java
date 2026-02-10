package org.bbrun.ast;

public record LiteralNode(Object value, LiteralType type) implements ExpressionNode {
    public enum LiteralType {
        NUMBER, STRING, BOOLEAN, NULL
    }
}
