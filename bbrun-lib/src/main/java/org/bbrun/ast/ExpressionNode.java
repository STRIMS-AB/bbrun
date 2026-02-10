package org.bbrun.ast;

/** Base interface for expression nodes. */
public sealed interface ExpressionNode permits
        LiteralNode, IdentifierNode, MemberAccessNode, IndexAccessNode,
        FunctionCallNode, BinaryOpNode, UnaryOpNode, IsCheckNode, ContainsNode,
        MatchesSchemaNode, IgnoringNode, ObjectLiteralNode, ArrayLiteralNode,
        InterpolatedStringNode {
}
