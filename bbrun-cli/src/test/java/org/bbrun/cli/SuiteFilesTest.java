package org.bbrun.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which scripts of a folder a suite runs. */
class SuiteFilesTest {

    @TempDir
    Path folder;

    private Path script(String name) throws IOException {
        Path file = folder.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "print \"x\"\n");
    }

    private List<String> suite(Path of) throws IOException {
        return Main.RunCommand.expand(List.of(of)).stream()
                .map(f -> folder.relativize(f).toString().replace('\\', '/'))
                .toList();
    }

    @Test
    void aFolderGivesItsScriptsInNameOrder() throws IOException {
        script("b.bbrun");
        script("a.bbrun");
        script("orders/c.bbrun");
        script("notes.txt");

        assertEquals(List.of("a.bbrun", "b.bbrun", "orders/c.bbrun"), suite(folder));
    }

    @Test
    void includedScriptsAndMockRouteScriptsAreLeftOut() throws IOException {
        script("a.bbrun");
        script("_setup.bbrun");
        script("_shared/login.bbrun");
        script("mocks/payment-detail.bbrun");
        script("orders/mocks/order-detail.bbrun");
        script("mocks.bbrun");

        assertEquals(List.of("a.bbrun", "mocks.bbrun"), suite(folder));
    }

    @Test
    void aFolderGivenByNameRunsWhateverItIsCalled() throws IOException {
        script("mocks/payment-detail.bbrun");
        script("_reference/tests/a.bbrun");

        assertEquals(List.of("mocks/payment-detail.bbrun"), suite(folder.resolve("mocks")));
        assertEquals(List.of("_reference/tests/a.bbrun"), suite(folder.resolve("_reference")));
    }
}
