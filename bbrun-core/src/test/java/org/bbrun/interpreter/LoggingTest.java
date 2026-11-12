package org.bbrun.interpreter;

import com.sun.net.httpserver.HttpServer;
import org.bbrun.BBRun;
import org.bbrun.config.BBRunConfig;
import org.bbrun.config.ConfigLoader;
import org.bbrun.config.ConfigurationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The script's log: the lines it writes, the collector a host reads while it runs, the file the CLI writes. */
class LoggingTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            byte[] out = "{\"id\": \"o-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private BBRun bbrun(String extra) {
        String yaml = "defaultEnvironment: test\n" + extra + "environments:\n  test:\n    resources:\n"
                + "      shop: { baseUrl: \"http://127.0.0.1:" + server.getAddress().getPort() + "\" }\n";
        return BBRun.builder().withConfig(ConfigLoader.parse(yaml, dir)).withEnvironment("test").build();
    }

    private Path script(String text) throws IOException {
        Path file = dir.resolve("order.bbrun");
        Files.writeString(file, text);
        return file;
    }

    @Test
    void callsAndTheEndAreLogged() throws IOException {
        LogCollector log = new LogCollector();
        ExecutionResult r = bbrun("").execute(script("""
                step "order" {
                    shop.post("/orders", {"sku": "A"})
                    assert response.status == 201
                    print "ordered " + response.body.id
                }
                """), log);
        assertTrue(r.isSuccess(), r.errorMessage());
        List<String> lines = log.entries().stream().map(e -> e.level() + " " + e.message()).toList();
        assertEquals("STEP order", lines.get(0));
        assertTrue(lines.get(1).matches("CALL shop\\.post /orders → 201 \\(\\d+ ms\\)"), lines.get(1));
        assertEquals("INFO ordered o-1", lines.get(2));
        assertTrue(lines.get(3).matches("DONE passed in \\d+ ms · 1 assertion · 1 request"), lines.get(3));
        assertEquals(List.of(1L, 2L, 3L, 4L), log.entries().stream().map(LogEntry::seq).toList());
    }

    @Test
    void aFailureEndsWithItsDiagnostics() throws IOException {
        LogCollector log = new LogCollector();
        bbrun("").execute(script("let n = 3\nassert n == 4 : \"n is four\"\n"), log);
        LogEntry last = log.latest(1).getFirst();
        assertEquals("ERROR", last.level());
        assertTrue(last.message().startsWith("n is four (line 2: n == 4); got 3"), last.message());
    }

    @Test
    void aHostReadsTheLatestLinesWhileTheScriptRuns() throws Exception {
        LogCollector log = new LogCollector();
        Path file = script("""
                print "before"
                shop.get("/slow")
                print "after"
                """);
        CompletableFuture<ExecutionResult> run = CompletableFuture.supplyAsync(() -> {
            try {
                return bbrun("").execute(file, log);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(log.await(0, 5000), "the first line arrives while the call is waiting");
        List<LogEntry> soFar = log.after(0, 100);
        assertEquals("before", soFar.getFirst().message());
        assertTrue(soFar.stream().noneMatch(e -> e.message().equals("after")), "the script is still in its call");

        release.countDown();
        assertTrue(run.get(5, TimeUnit.SECONDS).isSuccess());
        List<LogEntry> rest = log.after(soFar.getLast().seq(), 100);
        assertEquals(List.of("CALL", "INFO", "DONE"), rest.stream().map(LogEntry::level).toList());
        assertEquals("after", rest.get(1).message());
    }

    @Test
    void theCollectorKeepsItsLatestLines() {
        LogCollector log = new LogCollector(3);
        for (int i = 1; i <= 5; i++) log.line("INFO", "line " + i);
        assertEquals(List.of("line 3", "line 4", "line 5"), log.entries().stream().map(LogEntry::message).toList());
        assertEquals(2, log.dropped());
        assertEquals(5, log.last());
        assertEquals(List.of("line 5"), log.after(4, 10).stream().map(LogEntry::message).toList());
        assertEquals(List.of("line 4"), log.after(3, 1).stream().map(LogEntry::message).toList());
    }

    @Test
    void theConfigNamesTheLogFolderAndAFileIsWrittenThere() throws IOException {
        BBRunConfig cfg = ConfigLoader.parse("logging:\n  dir: logs\nenvironments: {}\n", dir);
        assertEquals(dir.resolve("logs").toAbsolutePath().normalize(), cfg.logDir().toAbsolutePath().normalize());

        Path written;
        try (FileLog file = FileLog.in(cfg.logDir(), "order.bbrun")) {
            bbrun("").execute(script("shop.post(\"/orders\", {})\nprint \"done\"\n"), file);
            written = file.path();
        }
        assertTrue(written.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_order\\.log"), written.toString());
        List<String> text = Files.readAllLines(written);
        assertEquals(3, text.size(), String.join("\n", text));
        assertTrue(text.get(0).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} CALL  shop\\.post /orders → 201 .*"), text.get(0));
        assertTrue(text.get(1).endsWith(" INFO  done"), text.get(1));

        try (FileLog again = FileLog.in(cfg.logDir(), "order.bbrun")) {
            assertNotEquals(written, again.path(), "a second run in the same second gets its own file");
        }
    }

    @Test
    void plusJoinsListsAndRefusesWhatItCannotAdd() throws IOException {
        LogCollector log = new LogCollector();
        ExecutionResult joined = bbrun("").execute(script("let a = [1]\nlet a = a + [2, 3]\nassert a == [1, 2, 3]\n"), log);
        assertTrue(joined.isSuccess(), joined.errorMessage());
        ExecutionResult refused = bbrun("").execute(script("let a = [1] + 2\n"), new LogCollector());
        assertFalse(refused.isSuccess());
        assertTrue(refused.errorMessage().contains("+ adds numbers, joins strings or joins lists, not a list and long"),
                refused.errorMessage());
    }

    @Test
    void loggingTakesOnlyAFolder() {
        assertThrows(ConfigurationException.class, () -> ConfigLoader.parse("logging:\n  level: debug\nenvironments: {}\n", dir));
        assertThrows(ConfigurationException.class, () -> ConfigLoader.parse("logging: logs\nenvironments: {}\n", dir));
        assertNull(ConfigLoader.parse("environments: {}\n", dir).logDir());
    }
}
