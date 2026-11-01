package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.molecule.MoleculeTasks;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Client facade for submitting geometry preparation and asynchronous definition tasks.
 * <p>Client facade; the common executor also serves asynchronous definition APIs.
 */
final class GeometryPreparation {

    /** Private constructor to prevent instantiation of utility class. */
    private GeometryPreparation() {}

    /**
     * Submits a callable task for asynchronous execution.
     *
     * @param action callable task action
     * @param <T>    task result type
     * @return a {@link Future} representing pending completion of the task
     */
    static <T> Future<T> submit(Callable<T> action) {
        return MoleculeTasks.submit(action);
    }

    /**
     * Submits a callable task for foreground execution.
     *
     * @param action callable task action
     * @param <T>    task result type
     * @return a {@link Future} representing pending completion of the task
     */
    static <T> Future<T> submitForeground(Callable<T> action) {
        return MoleculeTasks.submitForeground(action);
    }

    /** Checks and throws if the current geometry preparation task has been cancelled. */
    static void checkCancelled() {
        MoleculeTasks.checkCancelled();
    }
}