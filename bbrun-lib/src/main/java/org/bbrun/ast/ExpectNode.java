package org.bbrun.ast;

import java.util.List;

public record ExpectNode(ExpressionNode expectedStatus, List<StatementNode> body, int line) implements StatementNode {
}
