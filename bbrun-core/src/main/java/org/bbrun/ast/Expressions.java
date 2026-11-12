package org.bbrun.ast;

import java.util.List;
import java.util.Map;

public final class Expressions {
    private Expressions() {}

    public record LiteralNode(Object value, int line) implements ExpressionNode {}

    public record IdentifierNode(String name, int line) implements ExpressionNode {}

    public record MemberAccessNode(ExpressionNode object, String member, int line) implements ExpressionNode {}

    public record IndexAccessNode(ExpressionNode object, ExpressionNode index, int line) implements ExpressionNode {}

    public record FunctionCallNode(String name, List<ExpressionNode> arguments, int line) implements ExpressionNode {}

    public record MethodCallNode(ExpressionNode object, String methodName, List<ExpressionNode> arguments, int line) implements ExpressionNode {}

    public record BinaryOpNode(String operator, ExpressionNode left, ExpressionNode right, int line) implements ExpressionNode {}

    public record UnaryOpNode(String operator, ExpressionNode operand, int line) implements ExpressionNode {}

    public record MatchesModelNode(ExpressionNode expression, String modelName, int line) implements ExpressionNode {}

    public record ContainsNode(ExpressionNode collection, ExpressionNode item, boolean negated, int line) implements ExpressionNode {}

    public record ObjectLiteralNode(Map<String, ExpressionNode> entries, int line) implements ExpressionNode {}

    public record ArrayLiteralNode(List<ExpressionNode> items, int line) implements ExpressionNode {}

    public record PathNode(String rawPath, int line) implements ExpressionNode {}
}
