package org.bbrun.ast;

import java.util.List;

public record CleanupNode(List<StatementNode> body, int line) implements StatementNode {
}
