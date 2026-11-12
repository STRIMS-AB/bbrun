package org.bbrun.interpreter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Writes a script's log to a file, one line each, {@code 2026-11-06 11:30:02.114 CALL  message},
 * flushed at once so {@code tail -f} follows the run. Close it when the script ends.
 */
public final class FileLog implements ScriptOutput, AutoCloseable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final Path path;
    private final BufferedWriter out;

    public FileLog(Path path) throws IOException {
        this.path = path;
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        this.out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    /**
     * A new log in {@code dir} for {@code scriptName}: {@code <dir>/<yyyy-MM-dd_HH-mm-ss>_<name>.log}
     * (the script's name without {@code .bbrun}; a counter is added when the name is taken).
     */
    public static FileLog in(Path dir, String scriptName) throws IOException {
        String base = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())
                .format(Instant.now()) + "_" + (scriptName == null ? "script" : scriptName.replaceAll("\\.bbrun$", "")
                .replaceAll("[^A-Za-z0-9._-]", "_"));
        Path path = dir.resolve(base + ".log");
        for (int i = 2; Files.exists(path); i++) path = dir.resolve(base + "-" + i + ".log");
        return new FileLog(path);
    }

    public Path path() {
        return path;
    }

    @Override
    public synchronized void line(String level, String message) {
        try {
            out.write(TIME.format(Instant.now()) + " " + String.format("%-5s", level) + " " + message);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
