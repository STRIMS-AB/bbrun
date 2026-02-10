package org.bbrun.ast;

/** Auth clause for request-level authentication. */
public sealed interface AuthClauseNode permits BearerClause, BasicClause, UsingClause, WithoutAuth {
}
