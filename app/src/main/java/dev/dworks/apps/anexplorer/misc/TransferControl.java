package dev.dworks.apps.anexplorer.misc;

import java.io.IOException;

/** Lets a running copy be paused, resumed and cancelled from elsewhere, and reports its progress */
public class TransferControl {

    private boolean paused;
    private boolean cancelled;
    private long done;
    private long total;
    private String current = "";

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    public synchronized void cancel() {
        cancelled = true;
        notifyAll();
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized long getDone() {
        return done;
    }

    public synchronized long getTotal() {
        return total;
    }

    /** Bytes still to come, known up front for files and as folders are listed */
    public synchronized void addTotal(long bytes) {
        if (bytes > 0) {
            total += bytes;
        }
    }

    public synchronized String getCurrent() {
        return current;
    }

    synchronized void startFile(String name) {
        current = name;
    }

    /** Called between blocks: waits while paused and fails once cancelled */
    synchronized void step(long bytes) throws IOException {
        done += bytes;
        try {
            while (paused && !cancelled) {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        if (cancelled) {
            throw new IOException("Cancelled");
        }
    }
}
