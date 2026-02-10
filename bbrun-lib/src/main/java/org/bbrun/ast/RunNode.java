package org.bbrun.ast;

import java.util.Map;

public record RunNode(String scriptPath, Map<String, ExpressionNode> params, int line) implements StatementNode {
}
