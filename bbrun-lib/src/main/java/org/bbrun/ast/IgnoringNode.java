package org.bbrun.ast;

import java.util.List;

public record IgnoringNode(ExpressionNode expression, List<String> fields) implements ExpressionNode {
}
