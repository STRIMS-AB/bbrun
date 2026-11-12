package org.bbrun.interpreter;

import org.bbrun.BBRun;
import org.bbrun.config.*;
import org.bbrun.validation.DrefRegistry;
import org.bbrun.validation.MappingRegistry;
import dev.dref.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ControlFlowTest {

    @Test
    void forLoopOverRangeIsInclusive() {
        BBRun bbrun = mockBBRun("orderService");

        String script = """
                for i in 1..3 {
                    print "iteration " + i
                }

                assert i == 3
                """;

        ExecutionResult res = bbrun.execute(script);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(1, res.passedAssertions());
    }

    @Test
    void ifElseBranchesCorrectly() {
        BBRun bbrun = mockBBRun("orderService");

        String script = """
                let value = 5
                if value > 10 {
                    assert value == 42
                } else if value == 5 {
                    assert value == 5
                } else {
                    assert value == 42
                }
                """;

        ExecutionResult res = bbrun.execute(script);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(1, res.passedAssertions());
    }

    @Test
    void forEachOverListWithBreak() {
        BBRun bbrun = mockBBRun("orderService");

        String script = """
                let ids = [ "pi_1", "pi_2", "pi_3" ]

                assert ids.length == 3

                for id in ids {
                    if id == "pi_2" {
                        break
                    }
                }

                assert id == "pi_2"
                """;

        ExecutionResult res = bbrun.execute(script);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(2, res.passedAssertions());
    }

    @Test
    void breakInsideNestedIfStopsTheLoop() {
        BBRun bbrun = mockBBRun("orderService");

        String script = """
                for i in 1..5 {
                    if i >= 2 {
                        break
                    }
                }

                assert i == 2
                """;

        ExecutionResult res = bbrun.execute(script);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(1, res.passedAssertions());
    }

    @Test
    void forEachFetchesAndIteratesMappedResourceList() {
        // The mapping is declared on the model it imports onto, so a resource only
        // has to name it. DRef parses both the model and the mapping.
        DrefRegistry registry = new DrefRegistry();
        registry.parseAndRegister("""
                {
                  "name": "commerce",
                  "models": [
                    {
                      "name": "PaymentIntent",
                      "authority": "external",
                      "properties": [
                        { "name": "id", "type": "String", "required": true, "key": true },
                        { "name": "amount", "type": "Number", "required": true },
                        { "name": "currency", "type": "String", "required": true },
                        { "name": "status", "type": "String", "required": true,
                          "rules": [ { "type": "enum", "param": [ "succeeded", "canceled" ] } ] }
                      ],
                      "mapsFrom": [
                        {
                          "name": "Stripe PaymentIntent",
                          "provider": "Stripe",
                          "fields": [
                            "id",
                            { "from": "amount", "transform": [{ "type": "divide", "param": 100 }] },
                            { "from": "currency", "transform": ["upperCase"] },
                            "status"
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        MappingRegistry mappings = new MappingRegistry();
        mappings.setDrefRegistry(registry);
        mappings.registerInlineMappings();

        ResourceDefinition payments = new ResourceDefinition(
                "paymentsApi", "mock", null, "Stripe PaymentIntent"
        );
        EnvironmentConfig env = new EnvironmentConfig("test", Map.of("paymentsApi", payments));
        BBRunConfig config = new BBRunConfig("1.0", "test", Map.of("test", env), null);
        BBRun bbrun = BBRun.builder()
                .withConfig(config)
                .withEnvironment("test")
                .withDrefRegistry(registry)
                .withMappingRegistry(mappings)
                .build();

        String script = """
                paymentsApi.post("/payments", {
                    "id": "pi_1", "amount": 1000, "currency": "usd", "status": "succeeded"
                })
                paymentsApi.post("/payments", {
                    "id": "pi_2", "amount": 2000, "currency": "eur", "status": "canceled"
                })

                PaymentIntent[] payments = paymentsApi.get("/payments")
                assert payments.length == 2

                for payment in payments {
                    if payment.status == "canceled" {
                        break
                    }
                }

                assert payment.status == "canceled"
                print "stopped at " + payment.id + " (" + payment.status + ")"
                """;

        ExecutionResult res = bbrun.execute(script);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(2, res.passedAssertions());
        assertEquals(3, res.requestCount());
    }

    @Test
    void lengthIsAMemberOfAStringAndOfAList() {
        BBRun bbrun = mockBBRun("orderService");

        ExecutionResult res = bbrun.execute("""
                let name = "hello"
                assert name.length == 5
                assert name.length() == 5
                assert "".length == 0
                assert [1, 2, 3].length == 3
                assert name.size == null
                """);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(5, res.passedAssertions());
    }

    private BBRun mockBBRun(String resourceName) {
        ResourceDefinition apiDef = new ResourceDefinition(
                resourceName, "mock", null, null, null
        );
        EnvironmentConfig env = new EnvironmentConfig("test", Map.of(resourceName, apiDef));
        BBRunConfig config = new BBRunConfig("1.0", "test", Map.of("test", env), null);
        return BBRun.builder()
                .withConfig(config)
                .withEnvironment("test")
                .build();
    }
}