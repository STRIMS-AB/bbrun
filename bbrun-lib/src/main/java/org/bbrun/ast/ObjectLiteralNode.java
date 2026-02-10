package org.bbrun.ast;

import java.util.Map;

public record ObjectLiteralNode(Map<String, ExpressionNode> properties) implements ExpressionNode {
}
