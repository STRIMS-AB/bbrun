package org.bbrun.runtime;

import org.bbrun.BBRun;
import org.bbrun.config.*;
import org.bbrun.interpreter.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceExecutionTest {

    @Test
    void testResourceAbstractionInvocation() {
        ResourceDefinition apiDef = new ResourceDefinition(
                "orderService", "mock", null, null, null
        );
        EnvironmentConfig env = new EnvironmentConfig("dev", Map.of("orderService", apiDef));
        BBRunConfig config = new BBRunConfig("1.0", "dev", Map.of("dev", env), null);

        BBRun bbrun = BBRun.builder()
                .withConfig(config)
                .withEnvironment("dev")
                .build();

        String script = """
            let order = {
                "id": "order_" + randomString(4),
                "sku": "ITEM-99",
                "quantity": 2
            }
            
            orderService.post("/orders", order)
            
            assert response.status == 201
            assert response.body.sku == "ITEM-99"
            
            orderService.get("/orders/${order.id}")
            
            assert response.status == 200
            assert response.body.sku == "ITEM-99"
            """;

        ExecutionResult res = bbrun.execute(script);
        assertTrue(res.isSuccess(), "Execution failed: " + res.errorMessage());
        assertEquals(4, res.passedAssertions());
        assertEquals(2, res.requestCount());
    }
}