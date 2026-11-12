package org.bbrun;

import org.bbrun.config.BBRunConfig;
import org.bbrun.interpreter.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class IntegrationEngineTest {

    @TempDir
    Path tmpDir;

    private void writeMinimalProject() throws IOException {
        Files.writeString(tmpDir.resolve("bbrun.yaml"), """
                version: "1.0"
                domain: models/domain.json
                environments:
                  local:
                    resources:
                      customersApi:
                        type: mock
                """);
        Path mocks = tmpDir.resolve("mocks");
        Files.createDirectories(mocks);
        Files.writeString(mocks.resolve("customersApi.yaml"), """
                routes:
                  - method: get
                    path: /customers
                    data: [{ "id": "cus_1" }]
                  - method: post
                    path: /customers
                    status: 201
                    expr: '{ "id": "cus_" + randomString(4), "email": body.email }'
                """);
    }

    @Test
    void configDrivenEngineReturnsFlowValue() throws IOException {
        writeMinimalProject();

        IntegrationEngine engine = IntegrationEngine.fromConfig(tmpDir.resolve("bbrun.yaml"))
                .build();

        ExecutionResult result = engine.execute("""
                let newCustomer = { "email": "a@b.com" }
                customersApi.post("/customers", newCustomer)
                assert response.status == 201
                return response.body
                """);

        assertTrue(result.isSuccess(), result.errorMessage());
        assertTrue(result.value() instanceof java.util.Map<?, ?> m && "a@b.com".equals(m.get("email")),
                "value was: " + result.value());
        assertEquals(1, result.requestCount());
    }

    @Test
    void fireAndForgetFlowHasNullValue() throws IOException {
        writeMinimalProject();

        IntegrationEngine engine = IntegrationEngine.fromConfig(tmpDir.resolve("bbrun.yaml"))
                .build();

        ExecutionResult result = engine.execute("""
                customersApi.get("/customers")
                assert response.status == 200
                """);

        assertTrue(result.isSuccess(), result.errorMessage());
        assertNull(result.value());
    }

    @Test
    void programmaticResourcesMergeWithConfigImportAndAdditionsOverride() throws IOException {
        writeMinimalProject();

        IntegrationEngine engine = IntegrationEngine.create()
                .definedIn(tmpDir.resolve("bbrun.yaml"))
                .resource(IntegrationEngine.httpRequest("paymentsApi", "https://payments.example.com")
                        .authBearer("tok"))
                .build();

        BBRunConfig config = engine.getConfig();
        var env = config.getEnvironment("local");
        assertNotNull(env.getResource("customersApi"));           // from the imported file
        assertEquals("http", env.getResource("paymentsApi").resolvedType()); // added programmatically
        assertEquals("https://payments.example.com", env.getResource("paymentsApi").baseUrl());
    }

    @Test
    void programmaticOnlyEngineNeedsNoConfigFile() {
        IntegrationEngine engine = IntegrationEngine.create()
                .resource(IntegrationEngine.httpRequest("otherApi", "https://other.example.com"))
                .build();

        assertEquals(1, engine.getConfig().getEnvironment("default").resources().size());
        assertEquals("https://other.example.com",
                engine.getConfig().getEnvironment("default").getResource("otherApi").baseUrl());
    }

    @Test
    void statusAndHeaderStatementsReachExecutionResult() {
        IntegrationEngine engine = IntegrationEngine.create()
                .resource(IntegrationEngine.httpRequest("api", "https://example.com"))
                .build();

        ExecutionResult result = engine.execute("""
                status(201)
                header("Location", "/orders/" + "abc")
                return { "id": "abc" }
                """);

        assertTrue(result.isSuccess(), result.errorMessage());
        assertEquals(201, result.statusCode());
        assertEquals("/orders/abc", result.responseHeaders().get("Location"));
        assertEquals(Map.of("id", "abc"), result.value());
    }

    @Test
    void hostInfersDefaultStatusWhenScriptSetsNone() {
        IntegrationEngine engine = IntegrationEngine.create()
                .resource(IntegrationEngine.httpRequest("api", "https://example.com"))
                .build();

        ExecutionResult withValue = engine.execute("return { \"id\": \"abc\" }");
        assertTrue(withValue.isSuccess());
        assertNull(withValue.statusCode());

        ExecutionResult noValue = engine.execute("print \"done\"");
        assertTrue(noValue.isSuccess());
        assertNull(noValue.statusCode());
        assertNull(noValue.value());
    }

    @Test
    void programmaticMockResourceExecutesRoutesSuccessfully() {
        IntegrationEngine engine = IntegrationEngine.create()
                .resource(IntegrationEngine.mockResource("billingMock", "http")
                        .route(java.util.Set.of("post"), "/v1/charges", 201, Map.of("id", "ch_123", "status", "succeeded"))
                        .exprRoute(java.util.Set.of("get"), "/v1/charges/{id}", 200, "{ \"id\": params.id, \"amount\": 5000 }"))
                .resource(IntegrationEngine.mockResource("jobsQueue", "queue")
                        .opRoute("send", Map.of("queued", true)))
                .build();

        ExecutionResult result = engine.execute("""
                billingMock.post("/v1/charges", { "amount": 5000 })
                assert response.status == 201
                assert response.body.id == "ch_123"

                billingMock.get("/v1/charges/ch_999")
                assert response.status == 200
                assert response.body.id == "ch_999"

                jobsQueue.send({ "task": "email_receipt" })
                assert response.body.queued == true

                return { "completed": true }
                """);

        assertTrue(result.isSuccess(), result.errorMessage());
        assertEquals(Map.of("completed", true), result.value());
    }
}
