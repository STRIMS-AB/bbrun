package org.bbrun.ast;

import java.util.List;
import java.util.Map;

public record ParallelNode(Map<String, ExpressionNode> options, List<StatementNode> body, int line)
        implements StatementNode {
}
