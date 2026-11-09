package io.github.wurtzitane.avogadro.client.export;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous multi-threaded pipeline for quantifying and writing GIF frames.
 * <p>
 * Two leased workspaces at most; the single writer always consumes submission order.
 */
final class GifFramePipeline implements AutoCloseable {

    /** Underlying GIF media encoder for indexed frame writing. */
    private final GifMediaEncoder encoder;

    /** Array of quantizer workspaces allocated for background threads. */
    private final GifMediaEncoder.Quantizer[] quantizers;

    /** Bounded thread pool executor for running quantizer tasks. */
    private final ThreadPoolExecutor workers;

    /** Deque tracking pending frames in flight. */
    private final ArrayDeque<Pending> pending = new ArrayDeque<>(2);

    /** Counter tracking total frames submitted and successfully written. */
    private int submitted, written;

    /**
     * Creates a {@link GifFramePipeline} with automatic worker thread count selection.
     *
     * @param encoder the target {@link GifMediaEncoder}
     */
    GifFramePipeline(GifMediaEncoder encoder) {
        this(encoder, Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2)));
    }

    /**
     * Calculates the optimal number of worker threads based on image dimensions.
     *
     * @param width image width in pixels
     * @param height image height in pixels
     * @return recommended worker count (1 or 2)
     */
    static int workerCount(int width, int height) {
        return (long) width * height > 1_048_576
                ? 1
                : Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
    }

    /**
     * Constructs a fully specified {@link GifFramePipeline} with a given quantizer count.
     *
     * @param encoder the target {@link GifMediaEncoder}
     * @param count number of quantizer workspaces (1 or 2)
     * @throws IllegalArgumentException if count is outside the range 1..2
     */
    GifFramePipeline(GifMediaEncoder encoder, int count) {
        if (count < 1 || count > 2) {
            throw new IllegalArgumentException("GIF export uses one or two quantizers");
        }
        this.encoder = encoder;
        quantizers = new GifMediaEncoder.Quantizer[count];
        for (int i = 0; i < count; i++) {
            quantizers[i] = encoder.quantizer();
        }
        workers = new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(count),
                runnable -> {
                    Thread thread = new Thread(runnable, "Avogadro GIF quantizer");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Submits a pixel buffer for asynchronous quantization and writing.
     * <p>
     * Ownership is transferred until release runs, including cancellation and failure.
     *
     * @param pixels the source {@link MediaPixels}
     * @param index zero-based frame index
     * @param release runnable action to release the pixel buffer when done
     * @throws IOException if frame submission order is violated or export fails
     */
    void write(MediaPixels pixels, int index, Runnable release) throws IOException {
        Pending frame = new Pending(index, release);
        try {
            if (index != submitted) {
                throw new IOException("GIF frames must be submitted in order");
            }
            if (pending.size() == quantizers.length) {
                drain();
            }
            GifMediaEncoder.Quantizer quantizer = quantizers[submitted % quantizers.length];
            // The slot is reused only after its previous indexed raster has reached the writer.
            frame.future = workers.submit(() -> {
                if (!frame.start()) {
                    throw new IOException("GIF export cancelled");
                }
                try {
                    return quantizer.quantize(pixels);
                } finally {
                    frame.release();
                }
            });
            pending.addLast(frame);
            submitted++;
        } catch (IOException | RuntimeException failure) {
            frame.release();
            throw failure;
        }
    }

    /**
     * Returns the count of successfully written frames.
     *
     * @return written frames count
     */
    int written() {
        return written;
    }

    /**
     * Finishes the pipeline, draining all pending frames and finalizing the encoder.
     *
     * @throws IOException if finalization fails
     */
    void finish() throws IOException {
        while (!pending.isEmpty()) {
            drain();
        }
        encoder.finish();
    }

    /** Drains the next pending frame from the queue, blocking until quantization completes. */
    private void drain() throws IOException {
        Pending frame = pending.removeFirst();
        try {
            encoder.writeIndexed(frame.future.get(), frame.index);
            written++;
        } catch (InterruptedException failure) {
            frame.future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("GIF export cancelled", failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("Could not quantize GIF frame " + frame.index, failure.getCause());
        } catch (CancellationException failure) {
            throw new IOException("GIF export cancelled", failure);
        } finally {
            if (!frame.started) {
                frame.release();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        workers.shutdownNow();
        for (Pending frame : pending) {
            frame.future.cancel(true);
            if (!frame.started) {
                frame.release();
            }
        }
        pending.clear();
        // A running callable owns its workspace until its row-level interrupt check exits.
        // Completed/queued scratch must not remain reachable through a cancelled job.
        java.util.Arrays.fill(quantizers, null);
    }

    /**
     * Represents a pending frame task in flight within the pipeline.
     */
    private static final class Pending {

        /** Zero-based frame index. */
        final int index;

        /** Release callback runnable. */
        private Runnable release;

        /** Flag indicating whether quantization has started. */
        volatile boolean started;

        /** Asynchronous future for the quantized buffered image result. */
        Future<BufferedImage> future;

        /**
         * Creates a {@link Pending} frame task.
         *
         * @param index frame index
         * @param release release runnable
         */
        Pending(int index, Runnable release) {
            this.index = index;
            this.release = release;
        }

        /**
         * Marks the task as started if not already released.
         *
         * @return {@code true} if started successfully, {@code false} otherwise
         */
        synchronized boolean start() {
            if (release == null) {
                return false;
            }
            started = true;
            return true;
        }

        /** Executes the release callback if present. */
        synchronized void release() {
            if (release == null) {
                return;
            }
            release.run();
            release = null;
        }
    }
}
