package org.bbrun.ast;

import java.util.List;

public final class Statements {
    private Statements() {}

    public record ScriptNode(List<StatementNode> statements) implements Node {
        @Override public int line() { return 1; }
    }

    public record VariableNode(String name, ExpressionNode value, int line) implements StatementNode {}

    public record TypedVariableDeclNode(String typeName, boolean array, String name, ExpressionNode value, int line) implements StatementNode {} 

    public record VerbRequestNode(String verb, String path, ExpressionNode body, String targetResource, int line) implements StatementNode {}

    public record MapNode(ExpressionNode source, String mappingKey, String varName, int line) implements StatementNode {}

    public record ValidateNode(ExpressionNode target, String modelName, String message, int line) implements StatementNode {}

    public record AssertNode(ExpressionNode condition, String message, int line, String source) implements StatementNode {
        public AssertNode(ExpressionNode condition, String message, int line) {
            this(condition, message, line, null);
        }
    }

    public record WarnNode(ExpressionNode condition, String message, int line) implements StatementNode {}

    public record PrintNode(ExpressionNode message, int line) implements StatementNode {}

    public record IfNode(ExpressionNode condition, List<StatementNode> thenBody, List<StatementNode> elseBody, int line) implements StatementNode {}

    public record ForNode(String varName, ExpressionNode start, ExpressionNode end, ExpressionNode collection, List<StatementNode> body, int line) implements StatementNode {}

    public record BreakNode(int line) implements StatementNode {}

    public record ReturnNode(ExpressionNode value, int line) implements StatementNode {}

    public record AbortNode(ExpressionNode message, int line) implements StatementNode {}

    public record SetStatusNode(ExpressionNode statusValue, int line) implements StatementNode {}

    public record SetHeaderNode(ExpressionNode name, ExpressionNode value, int line) implements StatementNode {}

    /** {@code goal "..."}: what the script is for, reported with its result. */
    public record GoalNode(ExpressionNode text, int line) implements StatementNode {}

    /** {@code step "name" { ... }}: a named part of the flow; a failure inside names the step. */
    public record StepNode(ExpressionNode name, List<StatementNode> body, int line) implements StatementNode {}

    /** {@code cleanup { ... }}: runs when the script ends, whether it passed or failed. */
    public record CleanupNode(List<StatementNode> body, int line) implements StatementNode {}

    /** {@code include "file.bbrun"}: runs another script in the same context. */
    public record IncludeNode(String path, int line) implements StatementNode {}

    public record ExpressionStatementNode(ExpressionNode expression, int line) implements StatementNode {}
}
