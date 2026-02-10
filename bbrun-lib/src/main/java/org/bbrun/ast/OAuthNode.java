package org.bbrun.ast;

import java.util.Map;

public record OAuthNode(String flowType, Map<String, ExpressionNode> config, int line) implements StatementNode {
}
