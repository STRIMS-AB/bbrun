package org.bbrun.parser;

import org.bbrun.BBRunException;
import org.bbrun.ast.ScriptNode;
import org.bbrun.ast.StatementNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and merges script files with _init.bbrun support.
 */
public class ScriptLoader {

    private static final String INIT_FILE = "_init.bbrun";

    /**
     * Load a script with _init.bbrun files merged.
     */
    public ScriptNode loadWithInit(Path scriptPath) {
        List<ScriptNode> scripts = new ArrayList<>();

        try {
            // 1. Find project root (directory containing _init.bbrun or build.gradle)
            Path root = findProjectRoot(scriptPath);

            // 2. Load root _init.bbrun if exists
            if (root != null) {
                Path rootInit = root.resolve(INIT_FILE);
                if (Files.exists(rootInit)) {
                    scripts.add(parse(rootInit));
                }
            }

            // 3. Load folder _init.bbrun if different from root
            Path scriptParent = scriptPath.getParent();
            if (scriptParent != null) {
                Path folderInit = scriptParent.resolve(INIT_FILE);
                if (Files.exists(folderInit)) {
                    Path rootInit = root != null ? root.resolve(INIT_FILE) : null;
                    if (!folderInit.equals(rootInit)) {
                        scripts.add(parse(folderInit));
                    }
                }
            }

            // 4. Load main script
            scripts.add(parse(scriptPath));

            // 5. Merge all scripts
            return mergeScripts(scripts, scriptPath.toString());

        } catch (IOException e) {
            throw new BBRunException("Failed to load script: " + scriptPath, e);
        }
    }

    /**
     * Parse a single script file.
     */
    public ScriptNode parse(Path path) throws IOException {
        String content = Files.readString(path);
        return parse(content, path.toString());
    }

    /**
     * Parse script content using ANTLR.
     */
    public ScriptNode parse(String content, String sourcePath) {
        return AstBuilder.parse(content, sourcePath);
    }

    /**
     * Find project root by looking for _init.bbrun or build.gradle.
     */
    private Path findProjectRoot(Path startPath) {
        Path current = startPath.getParent();
        while (current != null) {
            if (Files.exists(current.resolve(INIT_FILE)) ||
                    Files.exists(current.resolve("build.gradle.kts")) ||
                    Files.exists(current.resolve("build.gradle")) ||
                    Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * Merge multiple scripts into one.
     * - Statements from _init.bbrun run first
     * - Cleanup blocks run in reverse order (folder first, then root)
     */
    private ScriptNode mergeScripts(List<ScriptNode> scripts, String mainPath) {
        List<StatementNode> allStatements = new ArrayList<>();

        for (ScriptNode script : scripts) {
            allStatements.addAll(script.statements());
        }

        // Note: Cleanup handling will be done at runtime by the interpreter
        // The interpreter will identify CleanupNode statements and handle them
        // specially
        return new ScriptNode(mainPath, allStatements);
    }
}
