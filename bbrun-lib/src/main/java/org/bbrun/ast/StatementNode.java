package org.bbrun.ast;

/**
 * Base interface for all statement nodes.
 */
public sealed interface StatementNode permits
        BaseUrlNode,
        AuthNode,
        OAuthNode,
        VariableNode,
        RequestNode,
        AssertNode,
        WarnNode,
        PrintNode,
        IfNode,
        RepeatNode,
        ParallelNode,
        ExpectNode,
        MacroNode,
        RunNode,
        CleanupNode,
        ExpressionStatementNode {

    int line();
}
