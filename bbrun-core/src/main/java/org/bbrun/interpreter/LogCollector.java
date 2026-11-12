package org.bbrun.interpreter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Keeps a script's log in memory instead of writing it anywhere: the host reads the whole log for
 * its report once the script ends, and, while it runs, the lines after the last one it has seen
 * ({@link #after}), to show the latest in a console. Safe to write from the script's thread and
 * read from others. Keeps the {@code capacity} latest lines; older ones are dropped and counted.
 */
public final class LogCollector implements ScriptOutput {

    public static final int DEFAULT_CAPACITY = 10_000;

    private final int capacity;
    private final Deque<LogEntry> entries = new ArrayDeque<>();
    private long last;
    private long dropped;

    public LogCollector() {
        this(DEFAULT_CAPACITY);
    }

    public LogCollector(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be at least 1");
        this.capacity = capacity;
    }

    @Override
    public synchronized void line(String level, String message) {
        entries.addLast(new LogEntry(++last, System.currentTimeMillis(), level, message));
        if (entries.size() > capacity) {
            entries.removeFirst();
            dropped++;
        }
        notifyAll();
    }

    /** The lines with a {@code seq} above {@code after}, oldest first, at most {@code limit}. */
    public synchronized List<LogEntry> after(long after, int limit) {
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : entries) {
            if (e.seq() <= after) continue;
            if (out.size() >= limit) break;
            out.add(e);
        }
        return out;
    }

    /** The {@code n} latest lines, oldest first. */
    public synchronized List<LogEntry> latest(int n) {
        List<LogEntry> all = new ArrayList<>(entries);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    /** Every line kept, oldest first. */
    public synchronized List<LogEntry> entries() {
        return List.copyOf(entries);
    }

    /** The {@code seq} of the last line written, 0 before any. */
    public synchronized long last() {
        return last;
    }

    /** How many of the oldest lines were dropped to keep {@code capacity}. */
    public synchronized long dropped() {
        return dropped;
    }

    /**
     * Waits until a line after {@code after} is written, or {@code timeoutMillis} pass; answers
     * whether there is one. For a host that follows the log by long polling.
     */
    public synchronized boolean await(long after, long timeoutMillis) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMillis;
        while (last <= after) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return false;
            wait(left);
        }
        return true;
    }
}
