package org.bbrun.interpreter;

import org.bbrun.ast.Statements.ScriptNode;
import org.bbrun.parser.ScriptParser;
import org.bbrun.runtime.ResourceContext;
import org.bbrun.validation.DrefRegistry;
import org.bbrun.validation.DrefValidatorBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ControlStatementsTest {

    private Interpreter newInterpreter() {
        ExecutionContext ctx = new ExecutionContext(
                new ResourceContext(null), new DrefValidatorBridge(new DrefRegistry()));
        return new Interpreter(ctx);
    }

    private void runScript(Interpreter interpreter, String script) {
        ScriptNode node = ScriptParser.parse(script);
        for (org.bbrun.ast.StatementNode stmt : node.statements()) {
            interpreter.executeStatement(stmt);
        }
    }

    @Test
    void abortWithoutMessageEndsExecution() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> runScript(newInterpreter(), "abort"));
        assertTrue(ex.getMessage().startsWith("Aborted at line 1"));
    }

    @Test
    void abortWithReasonIncludesMessage() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> runScript(newInterpreter(), "abort \"payment not found\""));
        assertEquals("Aborted at line 1: payment not found", ex.getMessage());
    }

    @Test
    void abortStopsExecutionPrematurely() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> runScript(newInterpreter(), """
                abort "stopping here"
                assert 1 == 2
                """));
        assertTrue(ex.getMessage().contains("stopping here"));
        assertFalse(ex.getMessage().contains("Assertion"), "statements after abort must not run");
    }

    @Test
    void abortReasonCanBeAnExpression() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> runScript(newInterpreter(), "let id = \"pi_1\"\nabort \"failed for \" + id"));
        assertEquals("Aborted at line 2: failed for pi_1", ex.getMessage());
    }

    @Test
    void topLevelReturnEndsTheFlowWithItsValue() {
        Interpreter interpreter = newInterpreter();
        ScriptNode node = ScriptParser.parse("return 1 + 1\nlet unreachable = 99");
        ExecutionResult result = interpreter.execute(node);
        assertTrue(result.isSuccess());
        assertEquals(2, ((Number) result.value()).intValue());
    }

    @Test
    void returnAndAbortAreReservedWords() {
        assertThrows(RuntimeException.class, () -> ScriptParser.parse("let return = 1"));
        assertThrows(RuntimeException.class, () -> ScriptParser.parse("let abort = 1"));
    }
}