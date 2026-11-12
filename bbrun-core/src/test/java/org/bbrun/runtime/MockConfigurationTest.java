package org.bbrun.runtime;

import org.bbrun.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class MockConfigurationTest {

    @TempDir
    Path tmpDir;

    // ---------------------------------------------------------------
    // Config parsing
    // ---------------------------------------------------------------

    @Test
    void parsesRoutesAndResolvesFileAndScriptPathsAgainstBaseDir() throws IOException {
        writeCustomersFixture();
        writeDetailScript();

        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      customersApi:
                        type: mock
                        routes:
                          - method: get
                            path: /customers
                            file: fixtures/customers.json
                      paymentsApi:
                        type: mock
                        routes:
                          - methods: [get]
                            path: /v1/payments/{id}
                            script: mocks/detail.bbrun
                          - method: post
                            path: /customers
                            status: 201
                            expr: '{ "id": "cus_" + randomString(6) }'
                """;

        BBRunConfig config = ConfigLoader.parse(yaml, tmpDir);

        ResourceDefinition customers = config.environments().get("local").resources().get("customersApi");
        assertEquals(1, customers.routes().size());
        assertEquals(List.of("get"), customers.routes().get(0).normalizedMethods().stream().toList());
        assertEquals("/customers", customers.routes().get(0).path());
        assertEquals(tmpDir.resolve("fixtures/customers.json").toString(), customers.routes().get(0).file());

        ResourceDefinition payments = config.environments().get("local").resources().get("paymentsApi");
        assertEquals(2, payments.routes().size());
        assertEquals(tmpDir.resolve("mocks/detail.bbrun").toString(), payments.routes().get(0).script());
        assertEquals(201, payments.routes().get(1).status());
    }

    @Test
    void rejectsRouteWithMultipleResponseSources() {
        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      customersApi:
                        type: mock
                        routes:
                          - method: get
                            path: /customers
                            data: []
                            file: fixtures/customers.json
                """;
        assertThrows(ConfigurationException.class, () -> ConfigLoader.parse(yaml, tmpDir));
    }

    // ---------------------------------------------------------------
    // Route response sources
    // ---------------------------------------------------------------

    @Test
    void fileRouteServesFixture() throws IOException {
        writeCustomersFixture();

        ResourceDefinition def = new ResourceDefinition(
                "customersApi", "mock", null, null, null, null,
                List.of(new MockRoute(Set.of("get"), "/customers", null, null,
                        tmpDir.resolve("fixtures/customers.json").toString(), null, null, Map.of())));

        ResponseObject resp = context(def).invoke("customersApi", "get", List.of("/customers"), null);

        assertEquals(200, resp.status());
        @SuppressWarnings("unchecked")
        List<Object> customers = (List<Object>) resp.body();
        assertEquals(2, customers.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) customers.get(0);
        assertEquals("sarah@example.com", first.get("email"));
    }

    // ---------------------------------------------------------------
    // External mock configuration files
    // ---------------------------------------------------------------

    private static final String MOCK_RESOURCE_YAML = """
            version: "1.0"
            environments:
              local:
                resources:
                  customersApi:
                    type: mock
            """;

    @Test
    void loadsRoutesFromMocksConventionFile() throws IOException {
        writeMockFile(tmpDir, "customersApi.yaml", """
                routes:
                  - method: get
                    path: /customers
                    file: ../fixtures/customers.json
                """);
        writeCustomersFixture();

        ResourceDefinition customers = ConfigLoader.parse(MOCK_RESOURCE_YAML, tmpDir)
                .environments().get("local").resources().get("customersApi");

        assertEquals(1, customers.routes().size());
        MockRoute route = customers.routes().get(0);
        assertEquals("/customers", route.path());
        // Relative fixture paths inside the mock file resolve against the mocks/ dir
        assertEquals(tmpDir.resolve("fixtures/customers.json").toString(), route.file());
    }

    @Test
    void loadsRoutesFromExplicitMocksFileAndOverridesConvention() throws IOException {
        writeMockFile(tmpDir, "customersApi.yaml", "routes: []\n");
        writeMockFile(tmpDir, "custom.yaml", """
                - method: post
                  path: /customers
                  status: 201
                  data: { ok: true }
                """);

        ResourceDefinition customers = ConfigLoader.parse("""
                version: "1.0"
                environments:
                  local:
                    resources:
                      customersApi:
                        type: mock
                        mocks: mocks/custom.yaml
                """, tmpDir).environments().get("local").resources().get("customersApi");

        assertEquals(1, customers.routes().size());
        assertEquals(201, customers.routes().get(0).status());
    }

    @Test
    void mockFileCanBeARouteListWithoutRoutesKey() throws IOException {
        writeMockFile(tmpDir, "customersApi.yml", """
                - method: get
                  path: /customers
                  data: []
                """);

        ResourceDefinition customers = ConfigLoader.parse(MOCK_RESOURCE_YAML, tmpDir)
                .environments().get("local").resources().get("customersApi");

        assertEquals(1, customers.routes().size());
        assertEquals(List.of("get"), customers.routes().get(0).normalizedMethods().stream().toList());
    }

    @Test
    void rejectsResourceWithBothInlineRoutesAndMocksFile() {
        assertThrows(ConfigurationException.class, () -> ConfigLoader.parse("""
                version: "1.0"
                environments:
                  local:
                    resources:
                      customersApi:
                        type: mock
                        mocks: custom.yaml
                        routes:
                          - method: get
                            path: /customers
                            data: []
                """, tmpDir));
    }

    private static void writeMockFile(Path configDir, String name, String content) throws IOException {
        Path mocks = configDir.resolve("mocks");
        Files.createDirectories(mocks);
        Files.writeString(mocks.resolve(name), content);
    }

    @Test
    void exprRouteEchoesRequestBodyWithGeneratedId() {
        ResourceDefinition def = new ResourceDefinition(
                "customersApi", "mock", null, null, null, null,
                List.of(new MockRoute(Set.of("post"), "/customers", 201, null, null,
                        """
                        { "id": "cus_" + randomString(6), "email": body.email, "firstName": body.firstName }""",
                        null, Map.of())));

        ResponseObject resp = context(def).invoke("customersApi", "post",
                List.of("/customers", Map.of("email", "new@example.com", "firstName", "New")),
                null);

        assertEquals(201, resp.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.body();
        assertEquals("new@example.com", body.get("email"));
        assertEquals("New", body.get("firstName"));
        assertTrue(String.valueOf(body.get("id")).startsWith("cus_"));
        assertEquals("application/json", resp.headers().get("Content-Type"));
    }

    @Test
    void queryParametersAreBoundForExprRoutes() {
        ResourceDefinition def = new ResourceDefinition(
                "customersApi", "mock", null, null, null, null,
                List.of(new MockRoute(Set.of("get"), "/probe", null, null, null, "query", null, Map.of())));

        ResponseObject resp = context(def).invoke("customersApi", "get", List.of("/probe?a=1&b=two"), null);

        assertEquals(200, resp.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.body();
        assertEquals("1", body.get("a"));
        assertEquals("two", body.get("b"));
    }

    @Test
    void scriptRouteRespondsUsingPathParams() throws IOException {
        writeDetailScript();

        ResourceDefinition def = new ResourceDefinition(
                "paymentsApi", "mock", null, null, null, null,
                List.of(new MockRoute(Set.of("get"), "/v1/payments/{id}", null, null, null, null,
                        tmpDir.resolve("mocks/detail.bbrun").toString(), Map.of())));

        ResourceContext context = context(def);

        ResponseObject found = context.invoke("paymentsApi", "get", List.of("/v1/payments/pi_9001"), null);
        assertEquals(200, found.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) found.body();
        assertEquals("pi_9001", body.get("id"));
        assertEquals(1550, ((Number) body.get("amount")).intValue());

        ResponseObject missing = context.invoke("paymentsApi", "get", List.of("/v1/payments/pi_nope"), null);
        assertEquals(404, missing.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) missing.body();
        assertEquals("payment not found", error.get("error"));
        assertEquals("pi_nope", error.get("id"));
    }

    @Test
    void scriptRouteIsNotBoundToDomainModels() throws IOException {
        Path script = tmpDir.resolve("mocks").resolve("typed.bbrun");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "validate response.body as PaymentIntent\n");

        ResourceDefinition def = new ResourceDefinition(
                "paymentsApi", "mock", null, null, null, null,
                List.of(new MockRoute(Set.of("get"), "/v1/payments/{id}", null, null, null, null,
                        script.toString(), Map.of())));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> context(def).invoke("paymentsApi", "get", List.of("/v1/payments/pi_9001"), null));
        assertTrue(ex.getMessage().contains("Unknown DRef domain model"),
                "mock script must not be bound to the project's domain models, but got: " + ex.getMessage());
    }

    @Test
    void parsesWhenConditionOnRoute() {
        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      customersApi:
                        type: mock
                        routes:
                          - method: get
                            path: /customers
                            when: 'headers["x-bbrun-script"] == "05-down.bbrun"'
                            status: 500
                            data: { error: "boom" }
                """;

        BBRunConfig config = ConfigLoader.parse(yaml, tmpDir);

        MockRoute route = config.environments().get("local").resources().get("customersApi").routes().get(0);
        assertEquals("headers[\"x-bbrun-script\"] == \"05-down.bbrun\"", route.when());
    }

    @Test
    void whenConditionSelectsRouteBasedOnScriptHeader() {
        ResourceDefinition def = new ResourceDefinition(
                "customersApi", "mock", null, null, null, null,
                List.of(
                        new MockRoute(Set.of("get"), "/customers", 500, Map.of("error", "boom"),
                                null, null, null, Map.of(),
                                "headers[\"x-bbrun-script\"] == \"05-down.bbrun\""),
                        new MockRoute(Set.of("get"), "/customers", 200, List.of("ok"),
                                null, null, null, Map.of())));

        ResourceContext context = context(def);

        // Another script -> clean route
        ResponseObject normal = context.invoke("customersApi", "get", List.of("/customers"),
                Map.of("x-bbrun-script", "03-up.bbrun"));
        assertEquals(200, normal.status());

        // The script expecting failure -> failing route
        ResponseObject failing = context.invoke("customersApi", "get", List.of("/customers"),
                Map.of("x-bbrun-script", "05-down.bbrun"));
        assertEquals(500, failing.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) failing.body();
        assertEquals("boom", body.get("error"));
    }

    @Test
    void whenConditionFallsBackToLifecycleWhenFalsy() {
        ResourceDefinition def = new ResourceDefinition(
                "customersApi", "mock", null, null, null, null,
                List.of(new MockRoute(Set.of("get"), "/customers", 500, Map.of("error", "boom"),
                        null, null, null, Map.of(),
                        "headers[\"x-bbrun-script\"] == \"05-down.bbrun\"")));

        // No script header -> when is falsy -> generic lifecycle fallback
        ResponseObject resp = context(def).invoke("customersApi", "get", List.of("/customers"), null);
        assertEquals(200, resp.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.body();
        assertEquals("mocked", body.get("status"));
    }

    @Test
    void whenConditionReadsThePathParameters() {
        ResourceDefinition def = new ResourceDefinition(
                "shopApi", "mock", null, null, null, null,
                List.of(
                        new MockRoute(Set.of("get"), "/products/{id}", 200, Map.of("name", "Travel mug"),
                                null, null, null, Map.of(), "params.id == \"P100\""),
                        new MockRoute(Set.of("get"), "/products/{id}", 404, Map.of("code", "PRODUCT_NOT_FOUND"),
                                null, null, null, Map.of()),
                        // a `when` is only asked about a request its path matches: this one would fail on a body-less GET
                        new MockRoute(Set.of("get"), "/orders/{id}", 200, Map.of("status", "confirmed"),
                                null, null, null, Map.of(), "params.id.startsWith(\"ord_\")")));

        ResourceContext context = context(def);
        assertEquals(200, context.invoke("shopApi", "get", List.of("/products/P100"), null).status());
        assertEquals(404, context.invoke("shopApi", "get", List.of("/products/other"), null).status());
        assertEquals(200, context.invoke("shopApi", "get", List.of("/orders/ord_1"), null).status());
    }

    // ---------------------------------------------------------------
    // Mocks of non-HTTP resources (mock: <kind>)
    // ---------------------------------------------------------------

    @Test
    void parsesMockKindAndOpRoutes() {
        String yaml = """
                version: "1.0"
                environments:
                  local:
                    resources:
                      ordersDb:
                        type: mock
                        mock: sql
                        routes:
                          - op: query
                            when: 'args[0] contains "pending"'
                            expr: '[{ "id": "ord_1", "status": "pending" }]'
                          - op: execute
                            data: { affectedRows: 1 }
                      jobsQueue:
                        type: mock
                        mock: queue
                """;

        ResourceDefinition orders = ConfigLoader.parse(yaml, tmpDir)
                .environments().get("local").resources().get("ordersDb");
        assertEquals("sql", orders.mockKind());
        assertEquals(2, orders.routes().size());
        assertEquals("query", orders.routes().get(0).op());
        assertEquals("execute", orders.routes().get(1).op());
        assertEquals(null, orders.routes().get(0).status());

        ResourceDefinition queue = ConfigLoader.parse(yaml, tmpDir)
                .environments().get("local").resources().get("jobsQueue");
        assertEquals("queue", queue.mockKind());
    }

    @Test
    void opRouteAnswersOperationCallsWithBindings() {
        ResourceDefinition def = new ResourceDefinition(
                "ordersDb", "mock", null, null, null, null,
                List.of(new MockRoute(null, null, null,
                        null, null,
                        "[{ \"id\": \"ord_1\", \"status\": \"pending\" }]",
                        null, Map.of(),
                        "args[0] contains \"pending\"",
                        "query")),
                null, null, null, "sql");

        ResourceContext context = context(def);

        // query on a matching route
        ResponseObject resp = context.invoke("ordersDb", "query",
                List.of("select * from orders where status = 'pending'"), null);
        assertTrue(resp.body() instanceof List<?> list && ((List<?>) list).size() == 1,
                "body was: " + resp.body());

        // the when doesn't mention 'pending' -> falls through to next route; none -> error
        RuntimeException noMatch = assertThrows(RuntimeException.class,
                () -> context.invoke("ordersDb", "query", List.of("select *"), null));
        assertTrue(noMatch.getMessage().contains("no route for operation 'query'"));

        // unknown op for the kind fails with the vocabulary hint
        RuntimeException unknownOp = assertThrows(RuntimeException.class,
                () -> context.invoke("ordersDb", "send", List.of("{}"), null));
        assertTrue(unknownOp.getMessage().contains("Unknown operation 'send'"));
    }

    @Test
    void rejectsOpRoutesOnHttpMockAndMockKeyOnNonMock() {
        assertThrows(ConfigurationException.class, () -> ConfigLoader.parse("""
                version: "1.0"
                environments:
                  local:
                    resources:
                      customersApi:
                        type: mock
                        routes:
                          - op: query
                            data: []
                """, tmpDir));

        assertThrows(ConfigurationException.class, () -> ConfigLoader.parse("""
                version: "1.0"
                environments:
                  local:
                    resources:
                      customersApi:
                        baseUrl: "https://api.example.com"
                        mock: sql
                """, tmpDir));
    }

    @Test
    void unmatchedRequestsFallBackToGenericLifecycle() throws IOException {
        writeCustomersFixture();

        ResourceDefinition def = new ResourceDefinition(
                "customersApi", "mock", null, null, null, null,
                List.of(new MockRoute(Set.of("get"), "/customers", null, null,
                        tmpDir.resolve("fixtures/customers.json").toString(), null, null, Map.of())));

        ResourceContext context = context(def);

        // POST has no route -> generic lifecycle: stores + indexes by id
        Map<String, Object> customer = Map.of("id", "cus_abc", "email", "a@b.com");
        ResponseObject created = context.invoke("customersApi", "post", List.of("/customers", customer), null);
        assertEquals(201, created.status());

        // GET by id has no route -> generic lifecycle store returns the posted object
        ResponseObject read = context.invoke("customersApi", "get", List.of("/customers/cus_abc"), null);
        assertEquals(200, read.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) read.body();
        assertEquals("cus_abc", body.get("id"));
    }

    @Test
    void theListFollowsPutPatchAndDeleteOfOneObject() {
        ResourceContext context = context(new ResourceDefinition(
                "paymentsApi", "mock", null, null, null, null, List.of()));

        context.invoke("paymentsApi", "post", List.of("/payments", Map.of("id", "pi_1", "amount", 500)), null);
        context.invoke("paymentsApi", "post", List.of("/payments", Map.of("id", "pi_2", "amount", 900)), null);

        // put replaces the object, in the list too
        ResponseObject put = context.invoke("paymentsApi", "put",
                List.of("/payments/pi_1", Map.of("id", "pi_1", "amount", 700)), null);
        assertEquals(200, put.status());
        assertEquals(List.of(Map.of("id", "pi_1", "amount", 700), Map.of("id", "pi_2", "amount", 900)),
                context.invoke("paymentsApi", "get", List.of("/payments"), null).body());

        // patch changes the fields it gives and keeps the others
        ResponseObject patch = context.invoke("paymentsApi", "patch",
                List.of("/payments/pi_2", Map.of("status", "captured")), null);
        assertEquals(200, patch.status());
        Map<String, Object> patched = Map.of("id", "pi_2", "amount", 900, "status", "captured");
        assertEquals(patched, patch.body());
        assertEquals(patched, context.invoke("paymentsApi", "get", List.of("/payments/pi_2"), null).body());
        assertEquals(List.of(Map.of("id", "pi_1", "amount", 700), patched),
                context.invoke("paymentsApi", "get", List.of("/payments"), null).body());

        // an object put under the collection for the first time joins the list
        context.invoke("paymentsApi", "put", List.of("/payments/pi_3", Map.of("id", "pi_3", "amount", 100)), null);
        assertEquals(List.of(Map.of("id", "pi_1", "amount", 700), patched, Map.of("id", "pi_3", "amount", 100)),
                context.invoke("paymentsApi", "get", List.of("/payments"), null).body());

        // delete removes it from the list and from its own path
        assertEquals(204, context.invoke("paymentsApi", "delete", List.of("/payments/pi_1"), null).status());
        assertEquals(List.of(patched, Map.of("id", "pi_3", "amount", 100)),
                context.invoke("paymentsApi", "get", List.of("/payments"), null).body());
        @SuppressWarnings("unchecked")
        Map<String, Object> gone = (Map<String, Object>)
                context.invoke("paymentsApi", "get", List.of("/payments/pi_1"), null).body();
        assertEquals("mocked", gone.get("status"), "nothing is stored there any more");

        // a collection emptied by delete answers an empty list
        context.invoke("paymentsApi", "delete", List.of("/payments/pi_2"), null);
        context.invoke("paymentsApi", "delete", List.of("/payments/pi_3"), null);
        assertEquals(List.of(), context.invoke("paymentsApi", "get", List.of("/payments"), null).body());
    }

    @Test
    void putAndPatchOutsideACollectionKeepToTheirPath() {
        ResourceContext context = context(new ResourceDefinition(
                "settingsApi", "mock", null, null, null, null, List.of()));

        context.invoke("settingsApi", "put", List.of("/settings/theme", Map.of("name", "dark")), null);
        assertEquals(Map.of("name", "dark"),
                context.invoke("settingsApi", "get", List.of("/settings/theme"), null).body());
        @SuppressWarnings("unchecked")
        Map<String, Object> parent = (Map<String, Object>)
                context.invoke("settingsApi", "get", List.of("/settings"), null).body();
        assertEquals("mocked", parent.get("status"), "nothing was posted to /settings: it has no list");

        // a patch of nothing stored answers its body and stores nothing
        ResponseObject patch = context.invoke("settingsApi", "patch", List.of("/settings/font", Map.of("size", 12)), null);
        assertEquals(Map.of("size", 12), patch.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> font = (Map<String, Object>)
                context.invoke("settingsApi", "get", List.of("/settings/font"), null).body();
        assertEquals("mocked", font.get("status"));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static ResourceContext context(ResourceDefinition def) {
        EnvironmentConfig env = new EnvironmentConfig("test", Map.of(def.name(), def));
        return new ResourceContext(env);
    }

    private void writeCustomersFixture() throws IOException {
        Path fixture = tmpDir.resolve("fixtures").resolve("customers.json");
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, """
                [
                  { "id": "cus_4829", "email": "sarah@example.com", "firstName": "Sarah", "lastName": "Connor" },
                  { "id": "cus_7312", "email": "john@example.com", "firstName": "John", "lastName": "Reese" }
                ]
                """);
    }

    private void writeDetailScript() throws IOException {
        Path script = tmpDir.resolve("mocks").resolve("detail.bbrun");
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                let ledger = {
                    "pi_9001": { "id": "pi_9001", "amount": 1550, "status": "succeeded" },
                    "pi_9002": { "id": "pi_9002", "amount": 8500, "status": "pending" }
                }
let entry = ledger[params.id]
if entry == null {
    respond(404, { "error": "payment not found", "id": params.id })
} else {
    return entry
}
                """);
    }
}