package io.github.wurtzitane.avogadro.client;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.github.wurtzitane.avogadro.Avogadro;

/** Client-thread owner of one replaceable job; workers never touch the live screen. */
final class PreviewPreparation<T> implements AutoCloseable {

    /** Callable action requested for preview preparation. */
    private Callable<T> requested;

    /** Asynchronous future for the running preparation task. */
    private Future<T> future;

    /** Flag indicating whether preparation failed with an exception. */
    private boolean failed;

    /**
     * Requests a new preview preparation task, canceling any existing active task.
     *
     * @param action callable task action
     */
    void request(Callable<T> action) {
        close();
        failed = false;
        requested = action;
        future = GeometryPreparation.submitForeground(action);
    }

    /**
     * Returns the completed preview preparation result, or {@code null} if pending or failed.
     *
     * @return prepared result instance, or {@code null}
     */
    T take() {
        if (requested == null)
            return null;
        if (future == null)
            future = GeometryPreparation.submitForeground(requested);
        if (future == null || !future.isDone())
            return null;
        try {
            return future.get();
        } catch (CancellationException cancelled) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException failure) {
            failed = true;
            Avogadro.LOGGER.warn("Could not prepare structure preview", failure.getCause());
            return null;
        } finally {
            future = null;
            requested = null;
        }
    }

    /**
     * Checks whether a preparation request is currently pending or active.
     *
     * @return {@code true} if pending, {@code false} otherwise
     */
    boolean isPending() {
        return requested != null;
    }

    /**
     * Checks whether the last preparation attempt failed.
     *
     * @return {@code true} if failed, {@code false} otherwise
     */
    boolean failed() {
        return failed;
    }

    /** Cancels any active futures and resets preparation state. */
    @Override
    public void close() {
        if (future != null)
            future.cancel(true);
        future = null;
        requested = null;
        failed = false;
    }
}
