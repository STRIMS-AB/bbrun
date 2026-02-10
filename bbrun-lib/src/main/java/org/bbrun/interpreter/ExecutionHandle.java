package org.bbrun.interpreter;

import org.bbrun.ExecutionResult;
import org.bbrun.events.EventListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handle for an async script execution.
 * Use {@link #poll()} to check progress or {@link #future()} to wait for
 * completion.
 */
public class ExecutionHandle {

    private final CompletableFuture<ExecutionResult> future;
    private final AtomicReference<ExecutionProgress> progress;
    private final CopyOnWriteArrayList<EventListener> listeners;
    private volatile boolean cancelled = false;

    public ExecutionHandle(CompletableFuture<ExecutionResult> future) {
        this.future = future;
        this.progress = new AtomicReference<>(ExecutionProgress.initial());
        this.listeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Get the completion future.
     */
    public CompletableFuture<ExecutionResult> future() {
        return future;
    }

    /**
     * Poll current progress without blocking.
     */
    public ExecutionProgress poll() {
        return progress.get();
    }

    /**
     * Check if execution is complete.
     */
    public boolean isComplete() {
        return future.isDone();
    }

    /**
     * Check if execution was cancelled.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Cancel execution.
     */
    public void cancel() {
        cancelled = true;
        future.cancel(true);
    }

    /**
     * Add an event listener for real-time updates.
     */
    public void addEventListener(EventListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove an event listener.
     */
    public void removeEventListener(EventListener listener) {
        listeners.remove(listener);
    }

    // Internal methods for interpreter to update progress
    void updateProgress(ExecutionProgress newProgress) {
        progress.set(newProgress);
    }

    CopyOnWriteArrayList<EventListener> getListeners() {
        return listeners;
    }
}
