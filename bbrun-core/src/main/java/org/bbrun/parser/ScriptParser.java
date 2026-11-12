package org.bbrun.parser;

import org.bbrun.ast.Statements.ScriptNode;
import org.antlr.v4.runtime.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ScriptParser {

    public static ScriptNode parse(Path scriptPath) throws IOException {
        String content = Files.readString(scriptPath);
        return parse(content);
    }

    public static ScriptNode parse(String scriptContent) {
        CharStream stream = CharStreams.fromString(scriptContent);
        BBRunLexer lexer = new BBRunLexer(stream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BBRunParser parser = new BBRunParser(tokens);

        List<String> errors = new ArrayList<>();
        BaseErrorListener errorListener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                errors.add("Line " + line + ":" + charPositionInLine + " " + msg);
            }
        };

        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        BBRunParser.ScriptContext tree = parser.script();

        if (!errors.isEmpty()) {
            throw new RuntimeException("Script syntax errors:\n  " + String.join("\n  ", errors));
        }

        AstBuilder builder = new AstBuilder();
        return builder.visitScript(tree);
    }
}
