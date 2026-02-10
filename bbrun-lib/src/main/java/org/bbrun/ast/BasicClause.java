package org.bbrun.ast;

public record BasicClause(ExpressionNode username, ExpressionNode password) implements AuthClauseNode {
}
