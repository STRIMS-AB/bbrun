package org.bbrun.ast;

import java.util.List;

/**
 * Root AST node representing a complete script.
 */
public record ScriptNode(
        String path,
        List<StatementNode> statements,
        List<CleanupNode> cleanupBlocks) {
    public ScriptNode(String path, List<StatementNode> statements) {
        this(path, statements, List.of());
    }
}
