package org.bbrun.ast;

import java.util.List;

public record RepeatNode(ExpressionNode count, List<StatementNode> body, int line) implements StatementNode {
}
