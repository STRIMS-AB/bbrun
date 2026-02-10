package org.bbrun.ast;

import java.util.List;

public record FunctionCallNode(String name, List<ExpressionNode> arguments) implements ExpressionNode {
}
