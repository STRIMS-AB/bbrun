package org.bbrun.ast;

import java.util.List;

public record IfNode(
        ExpressionNode condition,
        List<StatementNode> thenBlock,
        List<ElseIfClause> elseIfClauses,
        List<StatementNode> elseBlock,
        int line) implements StatementNode {
    public record ElseIfClause(ExpressionNode condition, List<StatementNode> block) {
    }
}
