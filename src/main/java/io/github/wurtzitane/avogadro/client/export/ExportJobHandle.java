package io.github.wurtzitane.avogadro.client.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import io.github.wurtzitane.avogadro.api.client.ExportJob;

/**
 * Synchronizes job observation separately from the encoder's publication lock.
 */
final class ExportJobHandle implements ExportJob {

    /** Dispatcher consumer for running listener callbacks on the appropriate thread. */
    private final Consumer<Runnable> dispatch;

    /** Handler invoked if a completion listener throws an uncaught exception. */
    private final Consumer<Throwable> listenerFailure;

    /** Attempts to cancel this job and returns whether the request was accepted. */
    private final BooleanSupplier cancel;

    /** List of registered completion listeners awaiting job termination. */
    private final List<Consumer<Result>> listeners = new ArrayList<>();

    /** Current progress state of the export job (volatile for thread-safe reads). */
    private volatile Progress progress;

    /** Final result of the export job once completed (volatile for thread-safe reads). */
    private volatile Result result;

    /**
     * Creates a {@link ExportJobHandle} for tracking export progress and listeners.
     *
     * @param frames the total number of frames to export
     * @param dispatch the task dispatch consumer
     * @param cancel the cancellation supplier
     * @param listenerFailure the listener exception handler consumer
     * @throws NullPointerException if any required functional parameter is null
     */
    ExportJobHandle(int frames, Consumer<Runnable> dispatch, BooleanSupplier cancel,
            Consumer<Throwable> listenerFailure) {
        this.dispatch = Objects.requireNonNull(dispatch);
        this.cancel = Objects.requireNonNull(cancel);
        this.listenerFailure = Objects.requireNonNull(listenerFailure);
        progress = new Progress(State.PREPARING, 0, 0, frames);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Progress progress() {
        return progress;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Result result() {
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean cancel() {
        return result == null && cancel.getAsBoolean();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onComplete(Consumer<Result> listener) {
        Objects.requireNonNull(listener, "listener");
        Result completed;
        synchronized (this) {
            completed = result;
            if (completed == null) {
                listeners.add(listener);
                return;
            }
        }
        Result terminal = completed;
        dispatch.accept(() -> deliver(listener, terminal));
    }

    /**
     * Updates the current progress state, captured frames count, and encoded frames count.
     *
     * @param state the new export {@link State}
     * @param captured number of frames captured so far
     * @param encoded number of frames encoded so far
     */
    synchronized void update(State state, int captured, int encoded) {
        if (result == null) {
            progress = new Progress(state, captured, encoded, progress.totalFrames());
        }
    }

    /**
     * Completes the export job with a terminal result, updating progress and dispatching all queued listeners.
     *
     * @param terminal the terminal {@link Result} of the export
     * @param captured final captured frames count
     * @param encoded final encoded frames count
     */
    void complete(Result terminal, int captured, int encoded) {
        List<Consumer<Result>> pending;
        synchronized (this) {
            if (result != null) {
                return;
            }
            result = Objects.requireNonNull(terminal, "terminal");
            progress = new Progress(terminal.state(), captured, encoded, progress.totalFrames());
            pending = List.copyOf(listeners);
            listeners.clear();
        }
        for (Consumer<Result> listener : pending) {
            dispatch.accept(() -> deliver(listener, terminal));
        }
    }

    /**
     * Safely delivers the terminal result to a completion listener, handling any thrown exceptions.
     *
     * @param listener the completion listener
     * @param terminal the terminal {@link Result}
     */
    private void deliver(Consumer<Result> listener, Result terminal) {
        try {
            listener.accept(terminal);
        } catch (Throwable failure) {
            listenerFailure.accept(failure);
        }
    }
}
