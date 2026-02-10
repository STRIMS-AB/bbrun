package org.bbrun.ast;

import java.util.List;

public record InterpolatedStringNode(List<StringPart> parts) implements ExpressionNode {
    public sealed interface StringPart permits TextPart, ExpressionPart {
    }

    public record TextPart(String text) implements StringPart {
    }

    public record ExpressionPart(ExpressionNode expression) implements StringPart {
    }
}
