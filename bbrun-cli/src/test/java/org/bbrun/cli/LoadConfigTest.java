package org.bbrun.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A load test finds its configuration the way a run does. */
class LoadConfigTest {

    @TempDir
    Path project;

    @Test
    void aLoadTestUsesTheConfigNextToItsScript() throws IOException {
        Files.writeString(project.resolve("bbrun.yaml"), """
                defaultEnvironment: local
                environments:
                  local:
                    resources:
                      shopApi:
                        type: mock
                """);
        Path script = Files.writeString(project.resolve("order.bbrun"), """
                shopApi.post("/orders", { "id": "o1" })
                assert response.status == 201
                """);

        // The current folder (this module) has no bbrun.yaml: without the one next to the
        // script, every iteration fails on an unknown environment
        int exit = new CommandLine(new Main()).execute("load", script.toString(), "-i", "4", "-u", "2", "--no-color");

        assertEquals(0, exit);
    }
}
