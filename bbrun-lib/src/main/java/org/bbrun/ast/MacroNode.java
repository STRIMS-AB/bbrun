package org.bbrun.ast;

import java.util.List;

public record MacroNode(String name, List<String> params, List<StatementNode> body, int line) implements StatementNode {
}
