package org.bbrun;

import org.bbrun.interpreter.ExecutionResult;
import org.bbrun.runtime.ServiceHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Services the host provides in Java, and script output sent to the host as it happens. */
public class HostServicesTest {

    @Test
    void scriptsCallHostServicesAndPrintToTheHost() {
        List<String> calls = new ArrayList<>();
        IntegrationEngine engine = IntegrationEngine.create()
                .service("kfig", (op, args) -> {
                    calls.add(op + args);
                    return Map.of("key", args.get(0), "value", args.get(1));
                })
                .build();
        List<String> lines = new ArrayList<>();
        ExecutionResult result = engine.execute("""
                kfig.set("payments.limit", 500)
                print "set " + response.body.key
                warn response.status == 201 : "not created"
                return response.body.value
                """, Map.of(), Map.of(), (level, message) -> lines.add(level + " " + message));

        assertTrue(result.isSuccess(), result.errorMessage());
        assertEquals(List.of("set[payments.limit, 500]"), calls);
        assertEquals(4, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(0).matches("CALL kfig\\.set payments\\.limit → 200 \\(\\d+ ms\\)"), lines.get(0));
        assertEquals(List.of("INFO set payments.limit", "WARN not created"), lines.subList(1, 3));
        assertTrue(lines.get(3).startsWith("DONE passed in "), lines.get(3));
        assertEquals(500, ((Number) result.value()).intValue());
    }

    @Test
    void aFailingServiceAnswersWithItsStatus() {
        IntegrationEngine engine = IntegrationEngine.create()
                .service("q10", (op, args) -> { throw new ServiceHandler.ServiceException(404, "No queue " + args.get(0)); })
                .build();
        ExecutionResult result = engine.execute("""
                q10.send("missing", {})
                return { "status": response.status, "message": response.body.message }
                """, Map.of(), Map.of(), (level, message) -> {});
        assertTrue(result.isSuccess(), result.errorMessage());
        assertEquals(Map.of("status", 404, "message", "No queue missing"), result.value());
    }

    @Test
    void anInterruptedRunStopsBeforeItsNextStatement() {
        IntegrationEngine engine = IntegrationEngine.create().build();
        Thread.currentThread().interrupt();
        try {
            List<String> lines = new ArrayList<>();
            ExecutionResult result = engine.execute("print \"never\"", Map.of(), Map.of(), (level, message) -> lines.add(level + " " + message));
            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().contains("Cancelled"));
            assertEquals(List.of("ERROR " + result.errorMessage()), lines, "nothing ran; the log says why it stopped");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aVariableBoundInALoopIsSeenInAnObjectLiteral() {
        List<Object> sent = new ArrayList<>();
        IntegrationEngine engine = IntegrationEngine.create()
                .service("q10", (op, args) -> { sent.add(args.get(1)); return Map.of(); })
                .build();
        ExecutionResult result = engine.execute("""
                for i in 1..2 {
                    let amount = i * 10
                    let noise = random(100) + 5
                    q10.send("q", { "amount": amount, "noise": noise })
                }
                return { "done": true }
                """, Map.of(), Map.of(), (level, message) -> {});
        assertTrue(result.isSuccess(), result.errorMessage());
        assertEquals(2, sent.size());
        for (Object m : sent) {
            Map<?, ?> map = (Map<?, ?>) m;
            assertNotNull(map.get("amount"), "a let in the loop: " + map);
            assertNotNull(map.get("noise"), "a let of random() in the loop: " + map);
        }
    }
}
