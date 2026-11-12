package org.bbrun.load;

import org.bbrun.BBRun;
import org.bbrun.config.BBRunConfig;
import org.bbrun.config.EnvironmentConfig;
import org.bbrun.config.ResourceDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LoadTestRunnerTest {

    @TempDir
    Path tmpDir;

    private Path script;
    private BBRun bbrun;

    @BeforeEach
    void setUp() throws Exception {
        ResourceDefinition apiDef = new ResourceDefinition("orderService", "mock", null, null, null);
        EnvironmentConfig env = new EnvironmentConfig("dev", Map.of("orderService", apiDef));
        BBRunConfig config = new BBRunConfig("1.0", "dev", Map.of("dev", env), null);
        bbrun = BBRun.builder()
                .withConfig(config)
                .withEnvironment("dev")
                .build();

        script = tmpDir.resolve("checkout.bbrun");
        Files.writeString(script, """
                let order = { "id": "order_" + randomString(4), "sku": "ITEM-99", "quantity": 2 }
                orderService.post("/orders", order)
                assert response.status == 201
                orderService.get("/orders/${order.id}")
                assert response.status == 200
                """);
    }

    @Test
    void runsExactIterationsAcrossUsers() {
        LoadTestConfig cfg = LoadTestConfig.builder()
                .script(script).users(10).iterations(50).build();

        LoadTestReport report = new LoadTestRunner(bbrun, cfg).run();

        assertEquals(50, report.total());
        assertEquals(50, report.succeeded());
        assertEquals(0, report.failed());
        assertEquals(100, report.totalRequests());
        assertEquals(100.0, report.successRate(), 0.001);
        assertEquals(10, report.users());
        assertTrue(report.avgLatencyMs() >= 0);
        assertTrue(report.p50Ms() >= 0 && report.p99Ms() >= report.p50Ms());
        assertTrue(report.iterationsPerSec() > 0);
    }

    @Test
    void boundsRunByDuration() {
        LoadTestConfig cfg = LoadTestConfig.builder()
                .script(script).users(8).duration(Duration.ofMillis(600)).build();

        LoadTestReport report = new LoadTestRunner(bbrun, cfg).run();

        assertTrue(report.elapsedMs() >= 500, "should run for the full duration, was " + report.elapsedMs() + "ms");
        assertTrue(report.total() > 0, "expected at least one iteration");
        assertEquals(0, report.failed());
        assertEquals(report.total(), report.samples().size());
    }

    @Test
    void reportPercentilesAggregateAllSamples() {
        LoadTestConfig cfg = LoadTestConfig.builder()
                .script(script).users(4).iterations(40).build();

        LoadTestReport report = new LoadTestRunner(bbrun, cfg).run();

        long min = report.samples().stream().mapToLong(LoadSample::latencyMs).min().orElse(-1);
        long max = report.samples().stream().mapToLong(LoadSample::latencyMs).max().orElse(-1);
        assertTrue(report.p50Ms() >= min && report.p50Ms() <= max);
        assertTrue(report.p99Ms() <= max);
        assertEquals(max, report.maxLatencyMs(), 0.001);
        assertEquals(report.total(), report.samples().size());
    }

    @Test
    void rejectsConfigWithoutBound() {
        LoadTestConfig.Builder builder = LoadTestConfig.builder().script(script);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void rejectsZeroUsers() {
        assertThrows(IllegalArgumentException.class,
                () -> LoadTestConfig.builder().script(script).users(0).iterations(5).build());
    }
}