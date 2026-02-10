package org.bbrun.ast;

/** HTTP request statement. */
public record RequestNode(
        String method,
        PathNode path,
        ExpressionNode body,
        AuthClauseNode authClause,
        int line) implements StatementNode {
}
