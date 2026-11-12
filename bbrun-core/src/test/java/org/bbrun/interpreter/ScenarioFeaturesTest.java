package org.bbrun.interpreter;

import com.sun.net.httpserver.HttpServer;
import org.bbrun.BBRun;
import org.bbrun.config.ConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** goal, step, cleanup, include, the scenario builtins and the HTTP request helpers. */
class ScenarioFeaturesTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final Map<String, String> seen = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            seen.put("authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            seen.put("x-api-key", String.valueOf(exchange.getRequestHeaders().getFirst("X-API-Key")));
            seen.put("accept-language", String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Language")));
            seen.put("content-type", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            seen.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"ok\": true, \"n\": [1, 2]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("X-Trace-Id", "t-1");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://app.example/back?code=abc%2B1&state=s");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private BBRun bbrun() {
        String yaml = "defaultEnvironment: test\nenvironments:\n  test:\n    resources:\n"
                + "      api: { baseUrl: \"http://127.0.0.1:" + server.getAddress().getPort() + "\" }\n";
        return BBRun.builder().withConfig(ConfigLoader.parse(yaml, dir)).withEnvironment("test").build();
    }

    private ExecutionResult run(String script, List<String> lines) throws IOException {
        Path file = dir.resolve("scenario.bbrun");
        Files.writeString(file, script);
        return bbrun().execute(file, (level, message) -> lines.add(level + " " + message));
    }

    private ExecutionResult run(String script) throws IOException {
        return run(script, new ArrayList<>());
    }

    @Test
    void goalsAndStepsAreReported() throws IOException {
        List<String> lines = new ArrayList<>();
        ExecutionResult r = run("""
                goal "Users get what they need"
                step "first" { assert 1 == 1 }
                step "second" { assert 2 == 2 }
                """, lines);
        assertTrue(r.isSuccess(), r.errorMessage());
        assertEquals(List.of("Users get what they need"), r.goals());
        assertEquals(List.of("first", "second"), r.steps().stream().map(StepResult::name).toList());
        assertTrue(r.steps().stream().allMatch(StepResult::passed));
        assertEquals(List.of("GOAL Users get what they need", "STEP first", "STEP second"), lines.subList(0, 3));
        assertTrue(lines.get(3).startsWith("DONE passed in "), "the log ends with how it ended: " + lines);
    }

    @Test
    void aFailureNamesItsStepWithTheExpressionAndTheValue() throws IOException {
        ExecutionResult r = run("""
                step "counting" {
                    let n = 3
                    assert n == 4
                }
                step "never" { print "no" }
                """);
        assertFalse(r.isSuccess());
        assertEquals("Step \"counting\" failed: Assertion failed (line 3: n == 4); got 3", r.errorMessage());
        assertEquals(1, r.steps().size());
        assertFalse(r.steps().get(0).passed());
    }

    @Test
    void theReasonIsTheAuthorsWordsWithoutDiagnostics() throws IOException {
        ExecutionResult r = run("""
                let weight = 0
                assert weight > 0 : "weight must be positive"
                """);
        assertFalse(r.isSuccess());
        assertEquals("weight must be positive", r.reason(), "what a host shows its callers");
        assertTrue(r.errorMessage().startsWith("weight must be positive (line 2: weight > 0); got 0"), r.errorMessage());

        ExecutionResult inStep = run("""
                step "checking" { assert 1 == 2 }
                """);
        assertEquals("Assertion failed (line 1: 1 == 2)", inStep.reason());
        assertTrue(inStep.errorMessage().startsWith("Step \"checking\" failed: "), inStep.errorMessage());
    }

    @Test
    void cleanupsRunLastFirstWithTheVariablesOfTheirRegistrationEvenAfterAFailure() throws IOException {
        List<String> lines = new ArrayList<>();
        ExecutionResult r = run("""
                let id = "a"
                cleanup { print "cleanup " + id }
                let id = "b"
                cleanup { print "cleanup " + id }
                assert false : "boom"
                """, lines);
        assertFalse(r.isSuccess());
        assertEquals("boom (line 5: false)", r.errorMessage());
        assertEquals(List.of("INFO cleanup b", "INFO cleanup a", "ERROR boom (line 5: false)"), lines);
    }

    @Test
    void aFailingCleanupFailsAScriptThatPassed() throws IOException {
        ExecutionResult r = run("""
                cleanup { assert 1 == 2 : "not cleaned" }
                assert true
                """);
        assertFalse(r.isSuccess());
        assertTrue(r.errorMessage().startsWith("cleanup: not cleaned"), r.errorMessage());
    }

    @Test
    void includeRunsAnotherFileInTheSameContextRelativeToTheIncludingFile() throws IOException {
        Files.createDirectories(dir.resolve("lib"));
        Files.writeString(dir.resolve("lib/setup.bbrun"), "let rows = readCsv(\"data.csv\")\n");
        Files.writeString(dir.resolve("lib/data.csv"), "code,name\nA1,\"Smith, Ann\"\nB2,\"say \"\"hi\"\"\"\n");
        ExecutionResult r = run("""
                include "lib/setup.bbrun"
                assert rows.length == 3
                assert rows[1] == ["A1", "Smith, Ann"]
                assert rows[2][1] == "say \\"hi\\""
                """);
        assertTrue(r.isSuccess(), r.errorMessage());
    }

    @Test
    void builtinsForScenarios() throws IOException {
        ExecutionResult r = run("""
                let items = [{"id": "a", "n": 1}, {"id": "b", "n": 2}]
                assert pluck(items, "id") == ["a", "b"]
                assert find(items, "n", 2).id == "b"
                assert find(items, "n", 3) == null
                assert keys({"x": 1, "y": 2}) == ["x", "y"]
                assert {"a": [1, 2.0]} == {"a": [1, 2]} : "deep, numbers by value"
                assert [1, 2] contains 2
                assert {"a": 1} contains "a"
                assert urlParam("https://x/back?code=abc%2B1&state=s", "code") == "abc+1"
                assert urlEncode("a b&c") == "a%20b%26c"
                assert "sk_dev_x_123".startsWith("sk_dev_")
                assert "a,b,c".split(",") == ["a", "b", "c"]
                assert "abcdef".substring(1, 3) == "bc"
                assert "a-b".replace("-", "+") == "a+b"
                assert parseJson(toJson({"k": [1]})).k[0] == 1
                assert base64Decode(base64Encode("héllo")) == "héllo"
                let jwt = "eyJhbGciOiJub25lIn0." + base64Encode("{\\"sub\\":\\"u1\\",\\"role\\":\\"owner\\"}").replace("=", "") + ".x"
                assert jwtClaims(jwt).role == "owner"
                assert "line\\nbreak".contains("\\n")
                """);
        assertTrue(r.isSuccess(), r.errorMessage());
    }

    @Test
    void anUnknownFunctionIsAnError() throws IOException {
        ExecutionResult r = run("let x = nosuch(1)");
        assertFalse(r.isSuccess());
        assertEquals("Unknown function nosuch()", r.errorMessage());
    }

    @Test
    void authenticateSendsCredentialsWithEveryCallToTheResource() throws IOException {
        ExecutionResult r = run("""
                authenticate(api, {"type": "bearer", "token": "t1"})
                api.get("/echo")
                assert response.status == 200
                assert response.headers["x-trace-id"] == "t-1" : "header names are case-insensitive"
                assert response.headers.X_TRACE_ID == null
                assert response.body.n == [1, 2]
                """);
        assertTrue(r.isSuccess(), r.errorMessage());
        assertEquals("Bearer t1", seen.get("authorization"));

        r = run("""
                authenticate("api", {"type": "apikey", "headerName": "X-API-Key", "headerValue": "k1"})
                api.get("/echo", headers({"Accept-Language": "de", "Authorization": "Bearer per-call"}))
                """);
        assertTrue(r.isSuccess(), r.errorMessage());
        assertEquals("k1", seen.get("x-api-key"));
        assertEquals("de", seen.get("accept-language"));
        assertEquals("Bearer per-call", seen.get("authorization"), "a call's own headers win");
    }

    @Test
    void formAndFileBodiesAreSentAsTheyAre() throws IOException {
        Files.writeString(dir.resolve("logo.svg"), "<svg/>");
        ExecutionResult r = run("api.post(\"/echo\", form({\"user\": \"a b\", \"x\": \"1&2\"}))");
        assertTrue(r.isSuccess(), r.errorMessage());
        assertEquals("application/x-www-form-urlencoded", seen.get("content-type"));
        assertEquals("user=a%20b&x=1%262", seen.get("body"));

        r = run("api.post(\"/echo\", file(\"logo.svg\"), headers({\"Accept-Language\": \"fr\"}))");
        assertTrue(r.isSuccess(), r.errorMessage());
        assertEquals("image/svg+xml", seen.get("content-type"));
        assertEquals("<svg/>", seen.get("body"));
        assertEquals("fr", seen.get("accept-language"));
    }

    @Test
    void redirectsAreNotFollowed() throws IOException {
        ExecutionResult r = run("""
                api.post("/redirect", form({"a": "1"}))
                assert response.status == 302
                assert urlParam(response.headers.location, "code") == "abc+1"
                """);
        assertTrue(r.isSuccess(), r.errorMessage());
    }

    @Test
    void sleepWaits() throws IOException {
        long start = System.currentTimeMillis();
        ExecutionResult r = run("sleep(50)");
        assertTrue(r.isSuccess(), r.errorMessage());
        assertTrue(System.currentTimeMillis() - start >= 50);
    }
}
