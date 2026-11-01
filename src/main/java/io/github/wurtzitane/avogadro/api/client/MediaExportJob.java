package io.github.wurtzitane.avogadro.api.client;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Represents a single media export job, including its retained outcome
 * after another export has started.
 */
public interface MediaExportJob {

    /**
     * Represents the lifecycle state of a media export job.
     */
    enum State {
        /** The export job is currently being prepared. */
        PREPARING,

        /** The export job is actively running. */
        RUNNING,

        /** A cancellation request has been issued and is being processed. */
        CANCELLING,

        /** The export job completed successfully. */
        SUCCEEDED,

        /** The export job was cancelled. */
        CANCELLED,

        /** The export job failed with an error. */
        FAILED
    }

    /**
     * Represents the current progress metrics of an export job.
     *
     * @param state          the current lifecycle state
     * @param capturedFrames the number of frames captured so far
     * @param encodedFrames  the number of frames encoded so far
     * @param totalFrames    the total number of frames to be processed
     */
    record Progress(State state, int capturedFrames, int encodedFrames, int totalFrames) {}

    /**
     * Represents the terminal result of an export job.
     *
     * @param state        the final lifecycle state
     * @param output       the path to the output file
     * @param reproduction the path to the reproduction data, if applicable
     * @param failure      the failure message, or {@code null} if successful
     */
    record Result(State state, Path output, Path reproduction, String failure) {}

    /**
     * Provides a thread-safe, non-blocking snapshot of the current export progress.
     *
     * @return the current {@link Progress} snapshot
     */
    Progress progress();

    /**
     * Retrieves the immutable terminal result of the export.
     *
     * <p>Returns {@code null} while the export is still pending. The terminal result
     * is retained indefinitely by this handle.
     *
     * @return the {@link Result} if completed, or {@code null} otherwise
     */
    Result result();

    /**
     * Requests cancellation of the export job in a thread-safe manner.
     *
     * @return {@code true} if cancellation was successfully requested, or {@code false}
     *         if publication or completion has already begun
     */
    boolean cancel();

    /**
     * Registers a listener to be invoked when the export job completes.
     *
     * <p>Each registration runs once on the client thread after cleanup,
     * even if registered late.
     *
     * @param listener the consumer to receive the final {@link Result}
     */
    void onComplete(Consumer<Result> listener);
}